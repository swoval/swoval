package com.swoval

import java.nio.file.{ Files, Path }
import java.util.concurrent.{ BlockingQueue, CountDownLatch, TimeUnit }

import scala.annotation.tailrec
import scala.concurrent.duration._
import utest._

import scala.language.higherKinds

package object test {
  final val DEFAULT_TIMEOUT = 1.second

  implicit class RichTraversbale[T, M[_]](val t: M[T]) {
    def ===(other: M[T]) = t ==> other
  }
  implicit class RichDuration(val d: Duration) extends AnyVal {
    private def toNanos = (d.toNanos - d.toMillis * 1e6).toInt

    def waitOn(lock: Object) = lock.synchronized(lock.wait(d.toMillis, toNanos))

    def waitOn(latch: CountDownLatch) =
      latch.await(d.toNanos, TimeUnit.NANOSECONDS)
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

  def using[C <: AutoCloseable, R](closeable: => C)(f: C => R): R = {
    val c = closeable
    try f(c)
    finally c.close()
  }

  def withTempFile[R](dir: Path)(f: Path => R): R = {
    val file = Files.createTempFile(dir, "file", "")
    try f(file)
    finally file.toFile.delete()
  }
  def withTempFile[R](f: Path => R): R = withTempDirectory[R]((dir: Path) => withTempFile(dir)(f))

  def withTempDirectory[R](f: Path => R): R = {
    val dir = Files.createTempDirectory("dir")
    withDirectory(dir)(f(dir.toRealPath()))
  }
  def withTempDirectory[R](dir: Path)(f: Path => R): R = {
    val subDir = Files.createTempDirectory(dir, "subdir")
    withDirectory(subDir)(f(subDir.toRealPath()))
  }

  def withDirectory[R](path: Path)(f: => R): R =
    try {
      path.toFile.mkdir()
      f
    } finally {
      path.toFile.delete()
      ()
    }
}
