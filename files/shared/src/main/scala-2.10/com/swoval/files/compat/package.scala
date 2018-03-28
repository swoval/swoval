package com.swoval.files

import java.util.function.{ Consumer, Predicate }

package object compat {
  implicit class FilterOps[T](f: T => Boolean) extends Predicate[T] {
    override def test(t: T): Boolean = f(t)
  }
  implicit class ConsumerOpts[T, R](f: T => R) extends Consumer[T] {
    override def accept(t: T): Unit = f(t)
  }
  implicit class RunnableOps[T, R](f: () => R) extends Runnable {
    override def run(): Unit = f()
  }
  implicit class EitherOps[L, R](val e: Either[L, R]) extends AnyVal {
    def toOption: Option[R] = e match {
      case Right(r) => Some(r)
      case _        => None
    }
  }
}
