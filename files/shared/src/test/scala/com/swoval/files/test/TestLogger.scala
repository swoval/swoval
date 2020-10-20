package com.swoval
package files
package test

import java.io.OutputStream
import java.util
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import com.swoval.logging.Logger
import com.swoval.logging.Loggers.Level

import scala.collection.JavaConverters._

trait TestLogger extends Logger
object TestLogger {
  private[this] val registeredTests = new ConcurrentHashMap[String, OutputStream].asScala
  lazy val defaultLevel: Level = Level.fromString(System.getProperty("swoval.log.level", "debug"))
  def log(message: String): Unit = {
    registeredTests.values.foreach { s =>
      s.write(message.getBytes)
      s.write('\n')
    }
  }
  def register(testName: String, outputStream: OutputStream): Unit = {
    registeredTests.synchronized(registeredTests += (testName -> outputStream))
  }
  def unregister(testName: String): Unit = registeredTests.synchronized(registeredTests -= testName)
}

final class CachingLogger(level: Level) extends TestLogger {
  def this() = this(TestLogger.defaultLevel)
  private[this] val lines: util.List[String] =
    Collections.synchronizedList[String](new util.ArrayList[String]())
  override def getLevel: Level = level
  override def verbose(message: String): Unit = lines.synchronized(lines.add(message))
  override def debug(message: String): Unit = {
    lines.synchronized(lines.add(message))
  }
  override def info(message: String): Unit = lines.synchronized(lines.add(message))
  override def warn(message: String): Unit = lines.synchronized(lines.add(message))
  override def error(message: String): Unit = lines.synchronized(lines.add(message))
  def getLines: Seq[String] = lines.synchronized(lines.asScala.toList)
}
