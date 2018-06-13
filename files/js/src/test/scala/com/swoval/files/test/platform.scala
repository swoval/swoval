package com.swoval.files.test

import scala.concurrent.duration._
import scala.concurrent.{ Promise, TimeoutException }
import scala.scalajs.js.timers._
import scala.util.{ Failure, Try }

object platform {
  type Bool = Boolean
  def newTimedPromise[T](p: Promise[T], timeout: FiniteDuration): TimedPromise[T] =
    new TimedPromise[T] {
      private[this] var cancelled = false
      private[this] val handle: SetTimeoutHandle = setTimeout(timeout) {
        if (!cancelled)
          tryComplete(Failure(new TimeoutException(s"Future timed out after $timeout")))
      }

      override def tryComplete(r: Try[T]) = {
        clearTimeout(handle)
        cancelled = true
        p.tryComplete(r)
      }
    }
}
