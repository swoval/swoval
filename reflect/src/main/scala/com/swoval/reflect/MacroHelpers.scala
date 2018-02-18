package com.swoval.reflect

import scala.annotation.tailrec
import scala.reflect.macros.blackbox

class MacroHelpers[C <: blackbox.Context](protected val c: C) {
  import c.universe._

  def box(name: TermName, tpe: Type): Tree = {
    tpe match {
      case t if t <:< weakTypeOf[Boolean] => q"_root_.java.lang.Boolean.valueOf($name)"
      case t if t <:< weakTypeOf[Byte]    => q"_root_.java.lang.Byte.valueOf($name)"
      case t if t <:< weakTypeOf[Char]    => q"_root_.java.lang.Character.valueOf($name)"
      case t if t <:< weakTypeOf[Double]  => q"_root_.java.lang.Double.valueOf($name)"
      case t if t <:< weakTypeOf[Float]   => q"_root_.java.lang.Float.valueOf($name)"
      case t if t <:< weakTypeOf[Int]     => q"_root_.java.lang.Integer.valueOf($name)"
      case t if t <:< weakTypeOf[Long]    => q"_root_.java.lang.Long.valueOf($name)"
      case t if t <:< weakTypeOf[Short]   => q"_root_.java.lang.Short.valueOf($name)"
      case _                              => q"$name"
    }
  }

  def typeToTerm(tpe: Type): TermName = typeToTerm(tpe.typeSymbol)
  def typeToTerm(symbol: Symbol): TermName = TermName(symbol.fullName.split("\\.").last.toLowerCase)
  def javaName(tpe: Type): String = {
    @tailrec
    def impl(t: Symbol, names: Seq[String] = Seq.empty): String = {
      if (t.owner.isPackage)
        s"${t.owner.fullName}${(t.name.toString +: names).mkString(".", "$", "")}"
      else if (t.owner.isType)
        impl(t.owner.asType, t.name.toString +: names)
      else
        c.abort(
          c.enclosingPosition,
          s"Can't determine java class name from ${qualified(tpe).toString.replace("_root_.", "")}")
    }
    impl(tpe.typeSymbol)
  }
  def qualified(tpe: Type): Tree = qualified(tpe.typeSymbol.fullName, isType = true)
  def qualified(term: String, isType: Boolean): Tree = {
    val (parts, name) = term.split("\\.") match { case l => (l.dropRight(1), l.last) }
    val prefix = parts match {
      case Array()  => q"_root_"
      case Array(p) => q"_root_.${TermName(p)}"
      case Array(h, rest @ _*) =>
        rest.foldLeft(q"_root_.${TermName(h)}") { case (a, t) => q"$a.${TermName(t)}" }
    }
    if (isType) tq"$prefix.${TypeName(name)}" else q"$prefix.${TermName(name)}"
  }
}
