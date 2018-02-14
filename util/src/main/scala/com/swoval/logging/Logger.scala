package com.swoval.util

import java.util.concurrent.ConcurrentHashMap

import ch.qos.logback.classic.Level

import scala.collection.JavaConverters._
import org.slf4j.LoggerFactory

trait Logger[T] extends SettableLevel {
  def debug(msg: => Any): Unit
  def debug(msg: => Any, ex: => Throwable): Unit

  def error(msg: => Any): Unit
  def error(msg: => Any, ex: => Throwable): Unit

  def info(msg: => Any): Unit
  def info(msg: => Any, ex: => Throwable): Unit

  def trace(msg: => Any): Unit
  def trace(msg: => Any, ex: => Throwable): Unit

  def warn(msg: => Any): Unit
  def warn(msg: => Any, ex: => Throwable): Unit
}

trait SettableLevel {
  def setLevel(level: Level): Unit
  def setDebug() = setLevel(Level.DEBUG)
  def setError() = setLevel(Level.ERROR)
  def setInfo() = setLevel(Level.INFO)
  def setTrace() = setLevel(Level.TRACE)
  def setWarn() = setLevel(Level.WARN)
}

object Logger extends SettableLevel {

  override def setLevel(level: Level) = rootLogger.setLevel(level)

  private class LogbackLogger[T](implicit m: Manifest[T]) extends Logger[T] {
    private val logger = LoggerFactory.getLogger(m.runtimeClass).asInstanceOf[LLogger]

    override def debug(msg: => Any): Unit = if (logger.isDebugEnabled) logger.debug(msg.toString)
    override def debug(msg: => Any, t: => Throwable): Unit =
      if (logger.isDebugEnabled) logger.debug(msg.toString, t)

    override def error(msg: => Any): Unit = if (logger.isErrorEnabled) logger.error(msg.toString)
    override def error(msg: => Any, t: => Throwable): Unit =
      if (logger.isErrorEnabled) logger.error(msg.toString, t)

    override def info(msg: => Any): Unit = if (logger.isInfoEnabled) logger.info(msg.toString)
    override def info(msg: => Any, t: => Throwable): Unit =
      if (logger.isInfoEnabled) logger.info(msg.toString, t)

    override def trace(msg: => Any): Unit = if (logger.isTraceEnabled) logger.trace(msg.toString)
    override def trace(msg: => Any, t: => Throwable): Unit =
      if (logger.isTraceEnabled) logger.trace(msg.toString, t)

    override def warn(msg: => Any): Unit = if (logger.isWarnEnabled) logger.warn(msg.toString)
    override def warn(msg: => Any, t: => Throwable): Unit =
      if (logger.isWarnEnabled) logger.warn(msg.toString, t)

    override def setLevel(level: Level) = logger.setLevel(level)
  }

  implicit def apply[T](implicit m: Manifest[T]): Logger[T] = {
    loggers get m match {
      case Some(logger) => logger.asInstanceOf[LogbackLogger[T]]
      case _ =>
        val logger = new LogbackLogger[T]
        loggers(m) = logger
        logger
    }
  }

  private type LLogger = ch.qos.logback.classic.Logger
  private val rootLogger = LoggerFactory
    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[LLogger]
  private val loggers = new ConcurrentHashMap[Manifest[_], LogbackLogger[_]].asScala
}
