package com.swoval.files

import utest.framework.ExecutionContext.RunNow

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
        promise.complete(Success(true))
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
          promises.enqueue(platform.newTimedPromise(p, timeout))
          p.future.map(f)(RunNow)
      })
    def add(t: T): Unit = lock.synchronized {
      promises.dequeueFirst(_ => true) match {
        case Some(timer) =>
          timer.tryComplete(Success(t))
        case _ => queue.enqueue(t)
      }
    }
  }
  def withTempFile[R](dir: Path)(f: Path => Future[R]): Future[R] =
    com.swoval.test.Files.withTempFile(dir.name)(s => f(Path(s)))

  def withTempFile[R](f: Path => Future[R]): Future[R] =
    com.swoval.test.Files.withTempFile(s => f(Path(s)))

  def withTempDirectory[R](f: Path => Future[R]): Future[R] =
    com.swoval.test.Files.withTempDirectory(d => f(Path(d)))

  def withTempDirectory[R](dir: Path)(f: Path => Future[R]): Future[R] =
    com.swoval.test.Files.withTempDirectory(dir.name)(d => f(Path(d)))

  def withDirectory[R](dir: Path)(f: => Future[R]): Future[R] =
    com.swoval.test.Files.withDirectory(dir.name)(f)

  def wrap[R](f: Path => R): Path => Future[R] = (path: Path) => {
    val p = Promise[R]()
    p.tryComplete(util.Try(f(path)))
    p.future
  }
  def withTempFileSync[R](dir: Path)(f: Path => R): Future[R] =
    withTempFile(dir)(wrap(f))

  def withTempFileSync[R](f: Path => R): Future[R] = withTempFile(wrap(f))

  def withTempDirectorySync[R](f: Path => R): Future[R] = withTempDirectory(wrap(f))

  def withTempDirectorySync[R](dir: Path)(f: Path => R): Future[R] =
    withTempDirectory(dir)(wrap(f))

  def withDirectorySync[R](dir: Path)(f: => R): Future[R] =
    com.swoval.test.Files.withDirectory(dir.name) {
      val p = Promise[R]
      p.complete(util.Try(f))
      p.future
    }
}
