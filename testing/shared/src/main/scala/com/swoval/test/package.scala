package com.swoval

import java.util.concurrent.{ BlockingQueue, TimeUnit }

import utest._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.util.Try

package object test {
  final val DEFAULT_TIMEOUT = 5.second

  /** Taken from shapeless */
  sealed trait <:!<[T, R]
  object <:!< {
    import scala.language.implicitConversions
    implicit def default[T, R]: <:!<[T, R] = new <:!<[T, R] {}
    implicit def alternative[T, R](implicit ev: T <:< R): <:!<[T, R] = new <:!<[T, R] {}
  }
  type NotFuture[T] = <:!<[T, Future[_]]
  object Implicits {
    implicit def executionContext: ExecutionContext = platform.executionContext
  }
  import Implicits.executionContext

  implicit class RichTraversable[T, M[_] <: Traversable[_]](val t: M[T]) {
    def ===(other: M[T]): Unit = {
      val tSet = t.toSet
      val oSet = other.toSet
      val tDiff = tSet diff oSet
      val oDiff = oSet diff tSet
      Seq(tDiff -> "extra", oDiff -> "missing") foreach {
        case (s, c) if s.nonEmpty =>
          println(
            s"The actual result had $c fields $s compared to the expected result.\n" +
              s"Found: $t\nExpected: $other ")
          s ==> Set.empty
        case _ =>
      }
      tSet ==> oSet
    }
  }
  implicit class RichOption[T](val t: Option[T]) {
    def ===(other: Option[T]): Unit = t ==> other
  }
  implicit class RichDuration(val d: Duration) extends AnyVal {
    private def toNanos = (d.toNanos - d.toMillis * 1e6).toInt
  }

  implicit class RichQueue[T](val q: BlockingQueue[T]) extends AnyVal {
    def poll(d: Duration): T = q.poll(d.toNanos, TimeUnit.NANOSECONDS)
    def pollFor(t: T, d: Duration): Unit = {
      val ceiling = System.nanoTime + d.toNanos
      @tailrec def impl(): Unit = {
        val d = (ceiling - System.nanoTime).nanos
        if ((d > 0.seconds) && (poll(d) != t)) impl()
      }
      impl()
    }
  }

  def using[C <: AutoCloseable, R: NotFuture](closeable: => C)(f: C => R): Future[R] = {
    val c = closeable
    val p = Promise[R]
    p.tryComplete(Try(f(c)))
    p.future.andThen { case _ => c.close() }
  }

  def usingAsync[C <: AutoCloseable, R](closeable: => C)(f: C => Future[R]): Future[R] = {
    val c = closeable
    f(c).andThen { case _ => c.close() }
  }

  object Files {
    def withTempFile[R](dir: String)(f: String => Future[R]): Future[R] = {
      val file: String = platform.createTempFile(dir, "file")
      f(file).andThen { case _ => platform.delete(file) }
    }

    def withTempFile[R](f: String => Future[R]): Future[R] =
      withTempDirectory { dir =>
        val file: String = platform.createTempFile(dir, "file")
        f(file).andThen { case _ => platform.delete(file) }
      }

    def withTempDirectory[R](f: String => Future[R]): Future[R] = {
      val dir = platform.createTempDirectory()
      withDirectory(dir)(f(dir))
    }

    def withTempDirectory[R](dir: String)(f: String => Future[R]): Future[R] = {
      val subDir: String = platform.createTempSubdirectory(dir)
      withDirectory(subDir)(f(subDir))
    }

    def withDirectory[R](path: String)(f: => Future[R]): Future[R] = {
      platform.mkdir(path)
      f.andThen { case _ => platform.delete(path) }
    }
  }
  def testOn(desc: String, platforms: Platform*)(tests: Any): Tests = macro Macros.testOnWithDesc
  def testOn(platforms: Platform*)(tests: Any): Tests = macro Macros.testOn
}
