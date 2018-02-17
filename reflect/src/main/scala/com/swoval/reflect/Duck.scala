package com.swoval.reflect

import scala.reflect.macros.blackbox
import scala.language.experimental.macros
import scala.languageFeature.reflectiveCalls

trait Duck[T, D] {
  def duck(t: T): D
}

object Duck {
  implicit def default[T, D]: Duck[T, D] = macro Duck.impl[T, D]
  def impl[T: c.WeakTypeTag, D: c.WeakTypeTag](c: blackbox.Context): c.Expr[Duck[T, D]] = {
    import c.universe._
    val (tType, dType) = (weakTypeOf[T], weakTypeOf[D])
    if (!dType.typeSymbol.isClass || !dType.typeSymbol.asClass.isTrait) {
      c.abort(c.enclosingPosition, s"Tried to duck type to non-trait type $dType")
    }
    def termName(tpe: Symbol) = TermName(c.freshName(tpe.fullName.split("\\.").last.toLowerCase))
    def duck(body: c.Tree*) = q"""
      new Duck[$tType, $dType] {
        ..$body
        override def toString() = "Duck[" + ${tType.typeSymbol.fullName} + ", " +
          ${dType.typeSymbol.fullName} + "]"
      }
    """

    val tree = tType match {
      case t if t <:< dType =>
        duck(q"override def duck(t: $tType): $dType = t")
      case t if weakTypeOf[Object] <:< t && t <:< weakTypeOf[Any] =>
        c.inferImplicitValue(weakTypeOf[reflectiveCalls], silent = true) match {
          case q"" =>
            c.warning(
              c.enclosingPosition,
              s"Generating duck type for $dType using reflective calls on $tType. To eliminate"
                + " this warning, import scala.language.reflectiveCalls"
            )
          case _ =>
        }
        val tName = TermName(c.freshName(t.typeSymbol.fullName.split("\\.").last.toLowerCase))
        val className = TermName(c.freshName("class"))
        val methodsName = TermName(c.freshName("methods"))
        val methods =
          q"""
             val $methodsName = {
               val classes = new scala.collection.mutable.ArrayBuffer[Class[_]]()
               var $className: Class[_] = $tName.getClass
               do {
                 classes += $className
                 $className = $className.getSuperclass
               } while ($className != null)
               classes.foldRight(Map.empty[String, Array[java.lang.reflect.Method]]) {
                 case (c, map) => map ++ c.getDeclaredMethods.groupBy(_.getName)
               }
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
            val boxed = (paramNames zip paramTypes).map { case (n, t) => Box(c)(n, t) }
            val realParamNames = sig.paramLists.map(_.map(t => termName(t)))
            val realParams = realParamNames.zip(sig.paramLists).map {
              case (names, types) =>
                names.zip(types) map { case (n, t) => q"val $n: ${t.typeSignature}" }
            }
            val rType = sig.finalResultType
            val alias = q"""
              lazy val $methodAlias: (..$paramTypes) => $rType = {
                ($methodsName.get($methodName) match {
                  case Some(methods) =>
                    val duckParamTypes: Array[Class[_]] =
                      Array(..${paramTypes.map(t => q"classOf[$t]")})
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
                  case Array(m) => m
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
        duck(q"override def duck($tName: $tType): $dType = new $dType { ..$decls }")
      case t =>
        val methods = tType.decls.filter(d => d.isMethod && !d.isConstructor)
        val tName = TermName(c.freshName(t.typeSymbol.fullName.split("\\.").last.toLowerCase))
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
                val name =
                  TermName(c.freshName(a.fullName.split("\\.").last.toLowerCase))
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

        duck(q"override def duck($tName: $tType): $dType = new $dType { ..$decls }")
    }
    c.Expr[Duck[T, D]](tree)
  }
}
