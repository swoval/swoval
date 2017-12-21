package com.swoval.files.test

import java.util.concurrent.{ Executors, ScheduledFuture, TimeUnit, TimeoutException }

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Try }

object platform {
  def newTimedPromise[T](p: Promise[T], timeout: FiniteDuration): TimedPromise[T] =
    new TimedPromise[T] {
      val timer = Executors.newSingleThreadScheduledExecutor()
      val future: ScheduledFuture[_] = timer.schedule(
        (() => tryComplete(Failure(new TimeoutException("Future timed out")))): Runnable,
        timeout.toNanos,
        TimeUnit.NANOSECONDS)
      override def tryComplete(r: Try[T]): Unit = {
        future.cancel(false)
        timer.shutdownNow()
        p.tryComplete(r)
        timer.awaitTermination(5, TimeUnit.SECONDS)
      }
    }
}
