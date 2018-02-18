package com.swoval.reflect

import scala.reflect.macros.blackbox
import scala.language.experimental.macros
import scala.languageFeature.reflectiveCalls
import scala.reflect.macros.runtime.AbortMacroException

trait Duck[T, D] extends Any {
  def duck(t: T): D
}
trait WeakDuck[T, D] extends Duck[T, D]
object WeakDuck {
  implicit def default[T, D]: WeakDuck[T, D] = macro DuckMacros.weakImpl[T, D]
}
object Duck {
  implicit def default[T, D]: Duck[T, D] = macro DuckMacros.impl[T, D, Duck[_, _]]
  trait AllowWeakConversions
  object features {
    implicit final case object AllowWeakConversions extends AllowWeakConversions
  }
}
object DuckMacros {
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
    println(expr)
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
                        None
                      }
                    }
                    case None => ???
                }) match {
                  case _root_.scala.Seq(m) => m
                  case _ => throw new IllegalArgumentException("whatever")
                }
              }
            """
        Seq(
          alias,
          q"""
                def ${TermName(methodName)}(...$realParams): ${sig.finalResultType} =
                  $methodAlias(..${realParamNames.flatten})
              """
        )
      }
      .toSeq
    val body = q"override def duck($tName: $tType): $dType = new $dType { ..$decls }"
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
        duck(c)(tType, dType, duckName, q"override def duck(t: $tType): $dType = t")
      case t if weakTypeOf[Object] <:< t && t <:< weakTypeOf[Any] =>
        c.inferImplicitValue(weakTypeOf[Duck.AllowWeakConversions]) match {
          case q"" =>
            c.abort(
              c.enclosingPosition,
              s"Tried to create Duck instance from generic type $tType." +
                " Import com.swoval.reflect.Duck.features.AllowWeakConversions to create a Duck" +
                " instance using java reflection."
            )
          case _ =>
            objectImpl[T, D, Duck[T, D]](c).tree
        }
      case _ =>
        val methods = tType.decls.filter(d => d.isMethod && !d.isConstructor)
        val tName = typeToTerm(tType)
        val decls = dType.decls.filter(m => m.isMethod && !m.isConstructor).flatMap { d =>
          (methods
            .filter(_.name == d.name) match {
            case m if m.isEmpty && d.isAbstract =>
              c.abort(c.enclosingPosition, s"$tType does not specify abstract $d in $dType")
            case m => m
          }).flatMap { m =>
              val dTypeSig = d.typeSignature
              val mTypeSig = m.typeSignature
              val argsMatch = dTypeSig.paramLists zip mTypeSig.paramLists forall {
                case (dp, mp) =>
                  dp zip mp forall { case (dt, mt) => mt.typeSignature <:< dt.typeSignature }
              }
              if (argsMatch && dTypeSig.finalResultType <:< mTypeSig.finalResultType) Some(m)
              else None
            }
            .map { m =>
              val args = m.typeSignature.paramLists.map(_.map { a =>
                val name = typeToTerm(a.typeSignature)
                name -> q"val $name: ${a.typeSignature}"
              })
              q"""
               override def ${m.name.encodedName.toTermName}(...${args
                .map(_.map(_._2))}): ${m.typeSignature.finalResultType} =
                $tName.$m(...${args.map(_.map(_._1))})
            """
            }
        } match {
          case d if d.isEmpty => Seq(q"")
          case d              => d
        }

        val body = q"override def duck($tName: $tType): $dType = new $dType { ..$decls }"
        duck(c)(tType, dType, duckName, body)
    }
    //println(tree)
    c.Expr[DT](tree)
  }
}
