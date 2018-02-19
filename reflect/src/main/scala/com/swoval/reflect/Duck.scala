package com.swoval.reflect

import scala.reflect.macros.blackbox
import scala.language.experimental.macros
import scala.languageFeature.reflectiveCalls
import scala.reflect.macros.runtime.AbortMacroException

/**
 * Converts an instance of type T into an instance of a trait of type D
 * using the structural information of D and T to extract the methods
 * of D from T.
 *
 * Example:
 *  {{{
 *    import com.swoval.reflect.Duck
 *    def using[T, R](t: => T)(f: T => R)(implicit duck: Duck[T, AutoCloseable]) = {
 *      val instance = t
 *      try f(instance) finally duck(instance).close()
 *    }
 *    class Foo {
 *      def doSomeWork(): Unit = println("Doing work!")
 *      def close(): Unit = println("Closing foo.")
 *    }
 *    using(new Foo)(f => f.doSomeWork()) // prints "Doing work!\nClosing foo."
 *  }}}
 * @tparam T the generic type that is being converted to D
 * @tparam D A generic type that must represent a trait (or java interface)
 */
trait Duck[T, D] extends Any {

  /**
   * Duck types an instance of T to a trait D if T structurally implements D.
   * @param t
   * @return
   */
  def apply(t: T): D
}

object Duck {
  implicit def default[T, D]: Duck[T, D] = macro DuckMacros.impl[T, D, Duck[_, _]]

  /**
   * Controls whether or not the macros to generate an instance of Duck[_, _] will fall back to
   * java reflection if the type is too generic.
   */
  sealed trait AllowReflection
  object features {

    /**
     * Allows the duck macro to fill in an empty method returning Unit if the
     * method it's trying to satisfy via duck typing returns Unit.
     */
    implicit final case object AllowWeakReflection extends AllowReflection

    /**
     * Allows the duck macro to use java reflection if the runtime type has a lower
     * bound of java.lang.Object.
     */
    implicit final case object AllowReflection extends AllowReflection
  }

  /**
   * Cast an instance of any type T to D if T structurally implements D.
   * @param t
   * @tparam T
   */
  implicit class DuckOps[T](val t: T) extends AnyVal {
    def duckType[D](implicit duck: Duck[T, D]): D = duck(t)
    def weakDuckType[D](implicit duck: WeakDuck[T, D]): D = duck(t)
  }
}

/**
 * Duck type from T to D, but always allows reflection. This is generally unsafe because the
 * runtime structure of java.lang.Object cannot be guaranteed.
 * @tparam T the generic type that is being converted to D
 * @tparam D A generic type that must represent a trait (or java interface)
 */
trait WeakDuck[T, D] extends Duck[T, D]
object WeakDuck {
  implicit def default[T, D]: WeakDuck[T, D] = macro DuckMacros.weakImpl[T, D]
}

private[reflect] object DuckMacros {
  def duck(
      c: blackbox.Context)(tType: c.Type, dType: c.Type, name: c.Tree, body: c.Tree*): c.Tree = {
    import c.universe._
    val sanitizedName = name.toString.split("\\.").last
    q"""
      new $name[$tType, $dType] {
        ..$body
        override def toString() = "" + $sanitizedName + "[" + ${tType.typeSymbol.fullName} + ", " +
          ${dType.typeSymbol.fullName} + "]"
      }
    """
  }
  def weakImpl[O: c.WeakTypeTag, D: c.WeakTypeTag](c: blackbox.Context): c.Expr[WeakDuck[O, D]] = {
    val expr = try {
      impl[O, D, WeakDuck[O, D]](c)
    } catch {
      case _: AbortMacroException =>
        objectImpl[O, D, WeakDuck[O, D]](c)
    }
    expr
  }
  def objectImpl[O: c.WeakTypeTag, D: c.WeakTypeTag, DT <: Duck[_, _]: c.WeakTypeTag](
      c: blackbox.Context): c.Expr[DT] = {
    val helpers = new MacroHelpers[c.type](c)
    import helpers._
    import c.universe._
    val (tType, dType) = (weakTypeOf[O], weakTypeOf[D])
    val tName = typeToTerm(tType)
    val className = TermName(c.freshName("class"))
    val methodsName = TermName(c.freshName("methods"))
    val methods = q"""
      val $methodsName = {
        val classes = new _root_.scala.collection.mutable.ArrayBuffer[Class[_]]()
        var $className: Class[_] = $tName.getClass
        do {
          classes += $className
          $className = $className.getSuperclass
        } while ($className != null)
        classes.flatMap(_.getDeclaredMethods.toSeq).groupBy(_.getName)
      }
    """
    val decls: Seq[c.Tree] = Seq(methods) ++ dType.decls
      .filter(m => m.isMethod && !m.isConstructor)
      .flatMap { d =>
        val methodName = d.name.toString
        val methodAlias = TermName(c.freshName(methodName))
        val sig = d.typeSignature
        val paramTypes = sig.paramLists.flatMap(_.map(_.typeSignature))
        val paramNames = paramTypes.map(p =>
          TermName(c.freshName(p.typeSymbol.fullName.split("\\.").last.toLowerCase)))
        val params = paramNames zip paramTypes map { case (n, t) => q"val $n: $t" }
        val boxed = (paramNames zip paramTypes).map { case (n, t) => box(n, t) }
        val realParamNames = sig.paramLists.map(_.map(typeToTerm))
        val realParams = realParamNames.zip(sig.paramLists).map {
          case (names, types) =>
            names.zip(types) map { case (n, t) => q"val $n: ${t.typeSignature}" }
        }
        val rType = qualified(sig.finalResultType)
        val notFound = {
          val weak = weakTypeOf[Duck.AllowReflection]
          c.inferImplicitValue(weak, silent = true) match {
            case q"" => q"None"
            case t
                if t.tpe =:= weakTypeOf[Duck.features.AllowWeakReflection.type]
                  && sig.finalResultType <:< weakTypeOf[Unit] && d.isAbstract =>
              q"Some((..$params) => {})"
            case _ => q"None"
          }
        }
        val cases = Seq(
          cq"${pq"_root_.scala.Seq(m)"} => m",
          if (!d.isAbstract)
            cq"${pq"_"} => (..$params) => super.${TermName(methodName)}(..$paramNames)"
          else {
            val msg = q"""$tName.toString + " does not have a " + ${d.toString}"""
            cq"${pq"_"} => throw new IllegalArgumentException($msg)"
          }
        )
        val alias = q"""
          lazy val $methodAlias: (..$paramTypes) => $rType = {
            ($methodsName.get($methodName) match {
                case Some(methods) =>
                  val duckParamTypes: _root_.scala.Seq[Class[_]] =
                    _root_.scala.Seq(..${paramTypes.map(t => q"classOf[${qualified(t)}]")})
                  methods.flatMap { case m =>
                    val paramTypes = m.getParameterTypes
                    if (paramTypes.zip(duckParamTypes).forall {
                      case (t, d) => d.isAssignableFrom(t)
                    } && m.getReturnType.isAssignableFrom(classOf[$rType])) {
                      Some((..$params) => m.invoke($tName, ..$boxed).asInstanceOf[$rType])
                    } else {
                      $notFound
                    }
                  }
                  case None => $notFound.toSeq
              }) match { case ..$cases }
          }
          """
        Seq(
          alias,
          q"""
            override def ${TermName(methodName)}(...$realParams): ${sig.finalResultType} =
              $methodAlias(..${realParamNames.flatten})
          """
        )
      }
      .toSeq
    val body = q"override def apply($tName: $tType): $dType = new $dType { ..$decls }"
    val tree = duck(c)(tType, dType, qualified(weakTypeOf[DT]), body)
    c.Expr[DT](tree)
  }
  def impl[T: c.WeakTypeTag, D: c.WeakTypeTag, DT <: Duck[_, _]: c.WeakTypeTag](
      c: blackbox.Context): c.Expr[DT] = {
    import c.universe._
    val helpers = new MacroHelpers[c.type](c)
    import helpers._
    val (tType, dType) = (weakTypeOf[T], weakTypeOf[D])
    if (!dType.typeSymbol.isClass || !dType.typeSymbol.asClass.isTrait) {
      c.abort(c.enclosingPosition, s"Tried to duck type to non-trait type $dType")
    }
    val duckName = qualified(weakTypeOf[DT])

    val tree = tType match {
      case t if t <:< dType =>
        duck(c)(tType, dType, duckName, q"@inline override def apply(t: $tType): $dType = t")
      case t if weakTypeOf[Object] <:< t && t <:< weakTypeOf[Any] =>
        c.inferImplicitValue(weakTypeOf[Duck.AllowReflection]) match {
          case q"" =>
            c.abort(
              c.enclosingPosition,
              s"Tried to create Duck instance from generic type $tType." +
                " Import com.swoval.reflect.Duck.features.AllowReflection to create a Duck" +
                " instance using java reflection."
            )
          case _ =>
            objectImpl[T, D, Duck[T, D]](c).tree
        }
      case _ =>
        val methods = tType.decls.filter(d => d.isMethod && !d.isConstructor)
        val tName = typeToTerm(tType)
        val decls = dType.decls.filter(m => m.isMethod && !m.isConstructor).flatMap { decl =>
          val method = (methods
            .filter(_.name == decl.name) match {
            case m if m.isEmpty && decl.isAbstract =>
              c.abort(c.enclosingPosition, s"$tType does not specify abstract $decl in $dType")
            case m => m
          }).flatMap { methodSymbol =>
              val declTypeSig = decl.typeSignature
              val methodTypeSig = methodSymbol.typeSignature
              val (declParams, methodParams) = (declTypeSig.paramLists, methodTypeSig.paramLists)
              val argsMatch = (declParams.lengthCompare(methodParams.length) == 0) &&
                declParams.zip(methodParams).forall {
                  case (dp, mp) if dp.lengthCompare(mp.length) == 0 =>
                    dp zip mp forall { case (dt, mt) => mt.typeSignature <:< dt.typeSignature }
                }
              if (argsMatch && declTypeSig.finalResultType <:< methodTypeSig.finalResultType)
                Some(methodSymbol)
              else None
            }
            .map { method =>
              val args = method.typeSignature.paramLists.map(_.map { a =>
                val name = typeToTerm(a.typeSignature)
                name -> q"val $name: ${a.typeSignature}"
              })
              q"""
                override def ${method.name.encodedName.toTermName}(...${args
                .map(_.map(_._2))}): ${method.typeSignature.finalResultType} =
                   $tName.$method(...${args.map(_.map(_._1))})
              """
            }
          if (method.isEmpty && decl.isAbstract) {
            c.abort(c.enclosingPosition,
                    s"No implementation for abstract method $decl in $dType is provided by $tType")
          }
          method
        } match {
          case d if d.isEmpty => Seq(q"")
          case d              => d
        }

        val body = q"override def apply($tName: $tType): $dType = new $dType { ..$decls }"
        duck(c)(tType, dType, duckName, body)
    }
    c.Expr[DT](tree)
  }
}
