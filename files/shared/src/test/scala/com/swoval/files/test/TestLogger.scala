package com.swoval
package files
package test

class TestLogger extends logging.Logger {
  override def debug(message: String): Unit = TestLogger.log(message)
}
object TestLogger {
  val lines = new java.util.Vector[String]
  def log(message: String): Unit = lines.synchronized {
    lines.add(System.currentTimeMillis() + " " + message)
    ()
  }
}
