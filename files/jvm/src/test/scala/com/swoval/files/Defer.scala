package com.swoval.files
import scala.concurrent.duration.FiniteDuration

object Defer {
  def apply[R](duration: FiniteDuration)(thunk: => R): Unit = new Thread() {
    setDaemon(true)
    start()
    val exception = new Exception()
    override def run(): Unit = {
      Thread.sleep(duration.toMillis)
      try {
        thunk
      } catch {
        case e: Exception =>
          exception.printStackTrace(System.err)
          throw e
      }
    }
  }
}
