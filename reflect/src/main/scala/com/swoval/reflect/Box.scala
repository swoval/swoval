package com.swoval.reflect

import scala.reflect.macros.blackbox

object Box {
  def apply(c: blackbox.Context)(name: c.TermName, tpe: c.Type): c.Tree = {
    import c.universe._
    tpe match {
      case t if t <:< weakTypeOf[Boolean] => q"_root_.java.lang.Boolean.valueOf($name)"
      case t if t <:< weakTypeOf[Byte]    => q"_root_.java.lang.Byte.valueOf($name)"
      case t if t <:< weakTypeOf[Char]    => q"_root_.java.lang.Char.valueOf($name)"
      case t if t <:< weakTypeOf[Double]  => q"_root_.java.lang.Double.valueOf($name)"
      case t if t <:< weakTypeOf[Float]   => q"_root_.java.lang.Float.valueOf($name)"
      case t if t <:< weakTypeOf[Int]     => q"_root_.java.lang.Integer.valueOf($name)"
      case t if t <:< weakTypeOf[Long]    => q"_root_.java.lang.Long.valueOf($name)"
      case t if t <:< weakTypeOf[Short]   => q"_root_.java.lang.Short.valueOf($name)"
      case _                              => q"$name"
    }
  }
}
