package com.swoval.reflect

import scala.reflect.macros.blackbox
import scala.language.experimental.macros

object Thunk {
  def apply[T](thunk: T, strict: Boolean): T = macro ThunkMacros.implStrict[T]
  def apply[T](thunk: T): T = macro ThunkMacros.impl[T]
}

object ThunkMacros {
  def implStrict[T: c.WeakTypeTag](c: blackbox.Context)(thunk: c.Expr[T],
                                                        strict: c.Expr[Boolean]): c.Expr[T] = {
    import c.universe._
    val tree = q"if ($strict) $thunk else ${impl(c)(thunk)}"
    c.Expr(tree)
  }
  def impl[T: c.WeakTypeTag](c: blackbox.Context)(thunk: c.Expr[T]): c.Expr[T] = {
    import c.universe._
    def loader = c.inferImplicitValue(weakTypeOf[DynamicClassLoader[_]])
    def fresh(name: String) = TermName(c.freshName(name))
    type Args = Seq[Seq[Tree]]
    def argClasses(args: Args) = args.flatten.map {
      case a if a.tpe <:< weakTypeOf[Boolean] => q"classOf[Boolean]"
      case a if a.tpe <:< weakTypeOf[Byte]    => q"classOf[Byte]"
      case a if a.tpe <:< weakTypeOf[Char]    => q"classOf[Char]"
      case a if a.tpe <:< weakTypeOf[Double]  => q"classOf[Double]"
      case a if a.tpe <:< weakTypeOf[Float]   => q"classOf[Float]"
      case a if a.tpe <:< weakTypeOf[Int]     => q"classOf[Int]"
      case a if a.tpe <:< weakTypeOf[Long]    => q"classOf[Long]"
      case a if a.tpe <:< weakTypeOf[Short]   => q"classOf[Short]"
      case a                                  => q"$a.getClass"
    }
    def boxed(args: Args) = args.flatten.map {
      case a if a.tpe <:< weakTypeOf[Boolean] =>
        q"java.lang.Boolean.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Byte]   => q"java.lang.Byte.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Char]   => q"java.lang.Char.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Double] => q"java.lang.Double.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Float]  => q"java.lang.Float.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Int]    => q"java.lang.Integer.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Long]   => q"java.lang.Long.valueOf($a)"
      case a if a.tpe <:< weakTypeOf[Short]  => q"java.lang.Short.valueOf($a)"
      case a                                 => a
    }
    def moduleApply(obj: Tree, method: TermName, args: Args) = {
      val moduleName = s"${obj.tpe.termSymbol.asModule.fullName}$$"
      val loaderName = fresh("loader")
      val classes = argClasses(args)
      val module = fresh("module")
      val instanceName = fresh("instanceName")
      val methodName = fresh("methodName")
      val tree = q"""
          val $loaderName = $loader
          val $module = $loaderName.loadClass($moduleName)
          val $instanceName = $module.getDeclaredField("MODULE$$").get(null)
          val $methodName = $module.getDeclaredMethod(${method.toString}, ..$classes)
          $methodName.invoke($instanceName, ..${boxed(args)})
        """
      tree
    }
    def withClass(clazz: Tree, args: Args)(f: (TermName, TermName) => Tree) = {
      val className = clazz.tpe.typeSymbol.fullName
      val loaderName = fresh("loader")
      val classInstance = fresh("class")
      val instanceName = fresh("instance")
      val classes = argClasses(args)
      val tree = q"""
        val $loaderName = $loader
        val $classInstance = $loaderName.loadClass($className)
        val $instanceName = $classInstance.getConstructor(..$classes).newInstance(..${boxed(args)})
        ${f(instanceName, classInstance)}
      """
      tree
    }
    def classApply(clazz: Tree, args: Args, method: TermName, methodArgs: Args) = {
      val methodClasses = argClasses(args)
      val className = fresh("routes")
      withClass(clazz, args)((instanceName, routesClass) => q"""
        val $className = $routesClass.getDeclaredMethod(${method.toString}, ..$methodClasses)
        $className.invoke($instanceName, ..${boxed(methodArgs)})
      """)
    }
    def cast(tree: Tree): Tree = q"$tree.asInstanceOf[${weakTypeOf[T]}]"
    val tree = cast(thunk.tree match {
      case q"${obj: Tree }.${method: TermName }(...${args: Args })"
          if obj.isTerm && obj.tpe.termSymbol.isModule =>
        moduleApply(obj, method, args)
      case q"new ${clazz: Tree }(...${args: Args })" if clazz.tpe <:< weakTypeOf[T] =>
        withClass(clazz, args)((i, _) => q"$i")
      case q"new ${clazz: Tree }(...${args: Args }).${method: TermName }(...${methodArgs: Args })" =>
        classApply(clazz, args, method, methodArgs)
    })
    c.Expr[T](tree)
  }
}
