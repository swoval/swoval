package com.swoval.files.apple

import scala.scalajs.js
import scala.scalajs.js.annotation._

@JSExportTopLevel("com.swoval.files.apple.FileEventsApi")
@JSExportAll
class FileEventsApi(handle: Double) extends AutoCloseable {
  private[this] var closed = false;
  override def close(): Unit = {
    if (!closed) {
      FileEventsApiFacade.close(handle)
      closed = true
    }
  }

  /**
   * Creates an event stream
   * @param path The directory to monitor for events
   * @param latency The minimum time in seconds between events for the path
   * @param flags The flags for the stream [[Flags.Create]]
   * @return handle that can be used to stop the stream in the future
   */
  def createStream(path: String, latency: Double, flags: Int) = {
    if (!closed) FileEventsApiFacade.createStream(path, latency, flags, handle)
    else
      throw new IllegalStateException(
        s"Tried to call create stream for $path on closed FileEventsApi")
  }

  /**
   * Stop monitoring the path that was previously created with [[createStream]]
   * @param streamHandle handle returned by [[createStream]]
   */
  def stopStream(streamHandle: Int) = {
    if (!closed) FileEventsApiFacade.stopStream(handle, streamHandle)
    else
      throw new IllegalStateException(
        s"Tried to call stop stream for handle $handle on closed FileEventsApi")
  }
}

@JSExportTopLevel("com.swoval.files.apple.FileEventsApi$")
object FileEventsApi {
  trait Consumer[T] {
    def accept(t: T): Unit
  }
  @JSExport("apply")
  def apply(consumer: Consumer[FileEvent], pathConsumer: Consumer[String]): FileEventsApi = {
    val jsConsumer: js.Function2[String, Int, Unit] = (s: String, i: Int) =>
      consumer.accept(new FileEvent(s, i))
    val jsPathConsumer: js.Function1[String, Unit] = (s: String) => pathConsumer.accept(s)
    new FileEventsApi(FileEventsApiFacade.init(jsConsumer, jsPathConsumer))
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

  def init(consumer: js.Function2[String, Int, Unit],
           pathConsumer: js.Function1[String, Unit]): Double = {
    fe.init(consumer, pathConsumer).asInstanceOf[Double]
  }

  def createStream(path: String, latency: Double, flags: Int, handle: Double): Int =
    fe.createStream(path, latency, flags, handle).asInstanceOf[Int]

  def stopStream(handle: Double, streamHandle: Int): Unit = {
    fe.stopStream(handle, streamHandle)
  }
}
