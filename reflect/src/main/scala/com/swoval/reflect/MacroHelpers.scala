package com.swoval.reflect

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

  def qualified(tpe: Type, isType: Boolean): Tree = {
    val (parts, name) = tpe.typeSymbol.fullName.split("\\.") match {
      case l => (l.dropRight(1), l.last)
    }
    val prefix = parts match {
      case Array(t) => q"_root_.${TermName(t)}"
      case Array(h, rest @ _*) =>
        rest.foldLeft(q"_root_.${TermName(h)}") { case (a, t) => q"$a.${TermName(t)}" }
    }
    if (isType) tq"$prefix.${TypeName(name)}" else q"$prefix.${TermName(name)}"
  }
}
