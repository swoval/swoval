package com.swoval
package files

import java.nio.file.{ Path, Paths }

import com.swoval.logging.Logger
import com.swoval.test.Implicits.executionContext
import com.swoval.test.NotFuture

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.language.experimental.macros
import scala.util.{ Failure, Success, Try }

package object test {
  implicit class FutureOps[R](val f: Future[R]) extends AnyVal {
    def logOnFailure()(implicit testLogger: TestLogger): Future[R] =
      f.recover {
        case e: Throwable =>
          testLogger match {
            case cl: CachingLogger => System.err.println(cl.getLines mkString "\n")
            case _                 =>
          }
          throw e
      }(utest.framework.ExecutionContext.RunNow)
  }
  def using[C <: AutoCloseable, R: NotFuture](closeable: => C)(f: C => R)(
      implicit testLogger: TestLogger): Future[R] = {
    val res = com.swoval.test
      .usingT(closeable)(f)
    res.onComplete {
      case Success(_) => if ("true" == System.getProperty("swoval.debug")) printLog(testLogger)
      case Failure(_) => printLog(testLogger)
    }(utest.framework.ExecutionContext.RunNow)
    res
  }
  def usingAsync[C <: AutoCloseable, R](closeable: => C)(f: C => Future[R])(
      implicit testLogger: TestLogger): Future[R] = {
    val res = com.swoval.test.usingAsyncT(closeable)(f)
    res.onComplete {
      case Success(_)            => if ("true" == System.getProperty("swoval.debug")) printLog(testLogger)
      case Failure(_: Throwable) => printLog(testLogger)
    }(utest.framework.ExecutionContext.RunNow)
    res
  }
  private def printLog(logger: Logger): Unit = logger match {
    case cl: CachingLogger => System.err.println(cl.getLines mkString "\n")
    case l_                =>
  }
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
