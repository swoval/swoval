package com.swoval
package files
package test
import java.io.OutputStream
import java.util
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import com.swoval.logging.Loggers
import com.swoval.logging.Loggers.Level

import scala.collection.JavaConverters._

class TestLogger extends logging.Logger {
  override def debug(message: String): Unit = TestLogger.log(message)
  override def getLevel: Loggers.Level = Level.VERBOSE
  override def verbose(message: String): Unit = TestLogger.log(message)
  override def info(message: String): Unit = TestLogger.log(message)
  override def warn(message: String): Unit = TestLogger.log(message)
  override def error(message: String): Unit = TestLogger.log(message)
}
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

object LoggingTestSuite {
  private[this] val defaultOutputStream: AtomicReference[String => OutputStream] =
    new AtomicReference[String => OutputStream]((_: String) => System.out)
  def setOutputStreamFactory(factory: String => OutputStream): Unit =
    defaultOutputStream.set(factory)
}
trait LoggingTestSuite extends utest.TestSuite {
  private[this] val name = new AtomicReference[String]("")
  private[this] val os = new AtomicReference[OutputStream](System.out)
  implicit val logger: logging.Logger = new CachingLogger(TestLogger.defaultLevel)
  final def setName(string: String): Unit = name.set(string)
  final def setOutputStream(outputStream: OutputStream): Unit = os.set(outputStream)
  final def register(): Unit = TestLogger.register(name.get(), os.get())
  final def unregister(): Unit = TestLogger.unregister(name.get())
}
class CachingLogger(level: Level) extends logging.Logger {
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
