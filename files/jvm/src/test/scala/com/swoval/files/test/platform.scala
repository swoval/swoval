package com.swoval.files.test

import java.util.concurrent.{ Executors, ScheduledFuture, TimeUnit, TimeoutException }

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Try }

object platform {
  type Bool = java.lang.Boolean
  def newTimedPromise[T](p: Promise[T], timeout: FiniteDuration): TimedPromise[T] =
    new TimedPromise[T] {
      val timeoutException = new TimeoutException("Future timed out")
      val timer = Executors.newSingleThreadScheduledExecutor()
      val runnable: Runnable = new Runnable {
        override def run(): Unit = tryComplete(Failure(timeoutException))
      }
      val future: ScheduledFuture[_] =
        timer.schedule(runnable, timeout.toNanos, TimeUnit.NANOSECONDS)
      override def tryComplete(r: Try[T]): Unit = {
        future.cancel(false)
        timer.shutdownNow()
        p.tryComplete(r)
        try {
          timer.awaitTermination(5, TimeUnit.SECONDS)
        } catch { case _: InterruptedException => }
      }
    }
}
