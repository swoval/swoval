package com.swoval
package files

import java.nio.file.{ Path, Paths }

import com.swoval.test.Implicits.executionContext
import com.swoval.test.NotFuture

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.util.{ Success, Try }

package object test {
  class CountDownLatch(private[this] var i: Int) {
    private[this] val promise = Promise.apply[Boolean]
    private[this] val lock = new Object
    def countDown(): Unit = lock.synchronized {
      i -= 1
      if (i == 0) {
        promise.tryComplete(Success(true))
      }
    }
    def getCount = i
    def waitFor[R](duration: FiniteDuration)(f: => R): Future[R] = {
      val tp: TimedPromise[Boolean] = platform.newTimedPromise(promise, duration)
      promise.future.map { r =>
        tp.tryComplete(Success(r))
        f
      }
    }
  }
  trait TimedPromise[T] {
    def tryComplete(r: Try[T]): Unit
  }
  class ArrayBlockingQueue[T](size: Int) {
    private[this] val queue: mutable.Queue[T] = mutable.Queue.empty
    private[this] val promises: mutable.Queue[TimedPromise[T]] = mutable.Queue.empty
    private[this] val lock = new Object
    def poll[R](timeout: FiniteDuration)(f: T => R): Future[R] =
      lock.synchronized(queue.headOption match {
        case Some(_) => Future.successful(f(queue.dequeue()))
        case _ =>
          val p = Promise[T]
          val tp: TimedPromise[T] = platform.newTimedPromise(p, timeout)
          promises.enqueue(tp)
          def dequeue[R](r: => R): R = { promises.dequeueAll(_ == tp); r }
          p.future.transform(r => dequeue(f(r)), e => dequeue(e))(
            utest.framework.ExecutionContext.RunNow)
      })
    def add(t: T): Unit = lock.synchronized {
      queue.enqueue(t)
      promises.dequeueFirst(_ => true).foreach { p =>
        queue.dequeue()
        p.tryComplete(Success(t))
      }
    }
  }
  def withTempFile[R](dir: Path)(f: Path => Future[R]): Future[Unit] =
    com.swoval.test.TestFiles.withTempFile(dir.toString)(s => f(Paths.get(s)).map(_ => ()))

  def withTempFile[R](f: Path => Future[R]): Future[Unit] =
    com.swoval.test.TestFiles.withTempFile(s => f(Paths.get(s)).map(_ => ()))

  def withTempDirectory[R](f: Path => Future[R]): Future[Unit] =
    com.swoval.test.TestFiles.withTempDirectory(d => f(Paths.get(d)).map(_ => ()))

  def withTempDirectory[R](dir: Path)(f: Path => Future[R]): Future[Unit] =
    com.swoval.test.TestFiles.withTempDirectory(dir.toString)(d => f(Paths.get(d)).map(_ => ()))

  def wrap[R](f: Path => R): Path => Future[Unit] = (path: Path) => {
    val p = Promise[Unit]()
    p.tryComplete(util.Try { f(path); () })
    p.future
  }
  def withTempFileSync[R: NotFuture](dir: Path)(f: Path => R): Future[Unit] =
    withTempFile(dir)(wrap(f))

  def withTempFileSync[R: NotFuture](f: Path => R): Future[Unit] =
    withTempFile(wrap(f))

  def withTempDirectorySync[R: NotFuture](f: Path => R): Future[Unit] = withTempDirectory(wrap(f))

  def withTempDirectorySync[R: NotFuture](dir: Path)(f: Path => R): Future[Unit] =
    withTempDirectory(dir)(wrap(f))
}
