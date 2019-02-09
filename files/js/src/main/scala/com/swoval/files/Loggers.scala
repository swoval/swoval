package com.swoval.files

import com.swoval.logging.Logger

object Loggers {

  private lazy val debugLogger: DebugLogger =
    if (System.getProperty("swoval.debug", "false") == "true") new DebugLoggerImpl(new Logger() {
      override def debug(message: String): Unit = println(message)
    })
    else new DebugLoggerImpl(null)

  private class DebugLoggerImpl(private val logger: Logger) extends DebugLogger {
    override def debug(message: String): Unit = {
      if (logger != null) logger.debug(message)
    }
    override def shouldLog(): Boolean = logger != null
  }
  def getDebug(): DebugLogger = debugLogger
}
