package com.swoval.files
import scala.concurrent.duration.FiniteDuration

object Defer {
  def apply[R](duration: FiniteDuration)(thunk: => R): Unit = new Thread() {
    setDaemon(true)
    start()
    override def run(): Unit = {
      Thread.sleep(duration.toMillis)
      thunk
    }
  }
}
