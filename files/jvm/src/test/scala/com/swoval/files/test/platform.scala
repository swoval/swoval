package com.swoval.files.test

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.concurrent.duration.{ Deadline, FiniteDuration }
import scala.util.{ Failure, Try }

object platform {
  type Bool = java.lang.Boolean
  def newTimedPromise[T](p: Promise[T], timeout: FiniteDuration): TimedPromise[T] =
    new TimedPromise[T] {
      private val timeoutException = new TimeoutException("Future timed out")
      private val deadline = timeout.fromNow
      private[this] val completed = new AtomicBoolean(false)
      new Thread {
        @tailrec
        override final def run(): Unit = {
          Thread.sleep(2)
          if (!completed.get()) {
            if (Deadline.now < deadline) run()
            else tryComplete(Failure(timeoutException))
          }
        }
      }.start()
      override def tryComplete(r: Try[T]): Unit =
        if (completed.compareAndSet(false, true)) p.tryComplete(r)
    }
}
