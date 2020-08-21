package com.swoval.files.apple

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import com.swoval.files.apple.FileEventMonitors.{ Handle, HandleImpl }
import com.swoval.functional.Consumer

import scala.scalajs.js
import scala.scalajs.js.annotation._

@JSExportTopLevel("com.swoval.files.apple.FileEventsMonitor")
@JSExportAll
class FileEventMonitor(handle: Double) extends AutoCloseable {
  private[this] var closed = false
  override def close(): Unit = {
    if (!closed) {
      FileEventsApiFacade.close(handle)
      closed = true
    }
  }

  /**
   * Creates an event stream
   * @param path The directory to monitor for events
   * @param latency The minimum time between events for the path
   * @param unit the time unit that `latency` is specified in
   * @param flags The flags for the stream [[Flags.Create]]
   * @return handle that can be used to stop the stream in the future
   */
  def createStream(path: Path, latency: Long, unit: TimeUnit, flags: Flags.Create): Handle = {
    val res: Int =
      if (!closed)
        FileEventsApiFacade.createStream(
          path.toString,
          unit.toNanos(latency) / 1.0e9,
          flags.value,
          handle
        )
      else
        throw new IllegalStateException(
          s"Tried to call create stream for $path on closed FileEventMonitor"
        )
    new HandleImpl(res)
  }

  /**
   * Stop monitoring the path that was previously created with [[createStream]]
   * @param streamHandle handle returned by [[createStream]]
   */
  def stopStream(streamHandle: Handle): Unit = {
    if (!closed) {
      streamHandle match {
        case h: HandleImpl => FileEventsApiFacade.stopStream(handle, h.value)
      }
    } else
      throw new ClosedFileEventMonitorException
  }
}
@JSExportTopLevel("com.swoval.files.apple.FileEventMonitors$")
object FileEventMonitors {
  trait Handle extends Any
  object Handles {
    val INVALID = new Handle {}
  }
  private[files] class HandleImpl(val value: Int) extends AnyVal with Handle
  @JSExport("get")
  def get(consumer: Consumer[FileEvent], pathConsumer: Consumer[String]): FileEventMonitor = {
    val jsConsumer: js.Function2[String, Int, Unit] = (s: String, i: Int) =>
      consumer.accept(new FileEvent(s, i))
    val jsPathConsumer: js.Function1[String, Unit] = (s: String) => pathConsumer.accept(s)
    new FileEventMonitor(FileEventsApiFacade.init(jsConsumer, jsPathConsumer))
  }
}

private object FileEventsApiFacade {
  private[this] val fe: js.Dynamic =
    try js.Dynamic.global.___global.require("lib/swoval_apple_file_system.node")
    catch {
      case _: Exception =>
        js.Dynamic.global.___global.require("./lib/swoval_apple_file_system.node")
    }

  def close(handle: Double): Unit = fe.close(handle)

  def init(
      consumer: js.Function2[String, Int, Unit],
      pathConsumer: js.Function1[String, Unit]
  ): Double = {
    fe.init(consumer, pathConsumer).asInstanceOf[Double]
  }

  def createStream(path: String, latency: Double, flags: Int, handle: Double): Int = {
    fe.createStream(path, latency, flags, handle).asInstanceOf[Int]
  }

  def stopStream(handle: Double, streamHandle: Int): Unit = {
    fe.stopStream(handle, streamHandle)
  }
}
