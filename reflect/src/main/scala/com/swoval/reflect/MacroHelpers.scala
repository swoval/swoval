package com.swoval.reflect

import scala.annotation.tailrec
import scala.reflect.macros.blackbox

/**
 * Provides useful helpers for building and manipulating scala syntax trees.
 * @param c
 * @tparam C
 */
class MacroHelpers[C <: blackbox.Context](protected val c: C) {
  import c.universe._

  /**
   * Boxes a primitive value (no additional boxing occurs for objects).
   * @param name TermName for the primitive value to be boxed
   * @param tpe Type to be boxed
   * @return Tree for the boxed type (no
   */
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

  /**
   * Converts a type to a fresh TermName for an instance of the type.
   * @param tpe
   * @return
   */
  def typeToTerm(tpe: Type): TermName = typeToTerm(tpe.typeSymbol)

  /**
   * Converts a symbol to a fresh TermName for an instance of the type. Example:
   * {{{
   *   val name = typeToTerm(com.foo.bar.Baz)
   * }}}
   * name will looks like TermName("baz\$fresh\$macro1").
   * @param symbol
   * @return
   */
  def typeToTerm(symbol: Symbol): TermName =
    TermName(c.freshName(symbol.fullName.split("\\.").last.toLowerCase))

  /**
   * Gets the java name for a class and works with some nesting. Example:
   * Suppose we have
   * {{{
   *   package foo.bar
   *
   *   object Baz {
   *     class Blah
   *   }
   *
   * }}}
   * then
   * {{{
   *   javaName(weakTypeOf[foo.bar.Baz.Blah}) == "foo.bar.Baz\$Blah"
   * }}}
   *
   * @param tpe
   * @return
   */
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

  /**
   * Returns the fully qualified TypeName of the provided type.
   * @param tpe
   * @return
   */
  def qualified(tpe: Type): Tree = qualified(tpe.typeSymbol.fullName, isType = true)

  /**
   * Returns the fully qualified Type or Term name of the provided string.
   * @param term
   * @param isType
   * @return
   */
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
