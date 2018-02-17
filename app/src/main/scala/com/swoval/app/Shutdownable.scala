package com.swoval.app

import scala.concurrent.duration._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait Shutdownable extends Any {
  def shutdown(): Unit
  def waitForShutdown(): Unit = waitForShutdown(Duration.Inf)
  def waitForShutdown(timeout: Duration): Boolean = true
}

trait DelegateShutdownable[T] {
  def shutdownable(t: T): Shutdownable
}
object DelegateShutdownable {
  implicit def default[T]: DelegateShutdownable[T] = macro DelegateShutdownable.impl[T]
  def impl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[DelegateShutdownable[T]] = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val tree = tpe match {
      case t if t <:< weakTypeOf[Shutdownable] =>
        q"""
          new DelegateShutdownable[$tpe] {
            override def shutdownable(t: $tpe): Shutdownable = t
            override def toString = "DelegateShutdownable[" + ${tpe.typeSymbol.fullName} + "]"
          }
        """
      case t =>
        val methods = Seq(
          TermName("shutdown") -> (List(List.empty), weakTypeOf[Unit]),
          TermName("waitForShutdown") -> (List(List.empty), weakTypeOf[Unit]),
          TermName("waitForShutdown") ->
            (List(List(weakTypeOf[scala.concurrent.duration.Duration])), weakTypeOf[Boolean])
        )
        val sName = TermName(c.freshName(t.typeSymbol.fullName.split("\\.").last.toLowerCase))
        val decls = t.decls.flatMap { d =>
          methods.filter(_._1.toString == d.name.toString).flatMap {
            case m @ (_, (params, rt)) =>
              val typeSig = d.typeSignature
              val paramTypes = typeSig.paramLists.map(_.map(_.typeSignature))
              if (typeSig.finalResultType <:< rt && paramTypes == params) Some(m) else None
          } map {
            case (name, (params, resultType)) =>
              val args = params.map(_.map { a =>
                val name =
                  TermName(c.freshName(a.typeSymbol.fullName.split("\\.").last.toLowerCase))
                name -> q"val $name: $a"
              })
              q"""
               override def $name(...${args.map(_.map(_._2))}): $resultType =
                $sName.$d(...${args.map(_.map(_._1))})
              """
          }
        }
        q"""
          new DelegateShutdownable[$tpe] {
            override def shutdownable($sName: $tpe): Shutdownable = new Shutdownable {
            ..$decls
            }
          }
        """
    }
    c.Expr[DelegateShutdownable[T]](tree)
  }
}
