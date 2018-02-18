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
  def qualified(tpe: Type): Tree = {
    @tailrec
    def impl(t: Symbol, name: Option[String] = None): Tree = {
      if (t.owner.isPackage)
        qualified(s"${t.fullName}${name.map(n => s".$n").getOrElse("")}", isType = true)
      else
        impl(t.owner.asType, None)
    }
    impl(tpe.typeSymbol)
  }
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
