package com.swoval.files

import scala.scalajs.js.timers._

private[files] class PeriodicTask(runnable: Runnable, pollIntervalMS: Long) extends AutoCloseable {
  private val handle: SetIntervalHandle = {
    runnable.run()
    setInterval(pollIntervalMS.toDouble) {
      runnable.run()
    }
  }
  override def close(): Unit = {
    clearInterval(handle)
  }
}
