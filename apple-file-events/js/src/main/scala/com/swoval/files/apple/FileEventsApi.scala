package com.swoval.files.apple

import scala.scalajs.js
import scala.scalajs.js.annotation._

@JSExportTopLevel("com.swoval.files.apple.FileEventsApi")
@JSExportAll
class FileEventsApi(handle: Double) extends AutoCloseable {
  override def close(): Unit = {
    FileEventsApiFacade.close(handle)
  }

  def createStream(path: String, latency: Double, flags: Int) = {
    FileEventsApiFacade.createStream(path, latency, flags, handle)
  }
  def stopStream(streamHandle: Int) = {
    FileEventsApiFacade.stopStream(handle, streamHandle)
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
  private[this] var closed = false
  private[this] val fe: js.Dynamic =
    try js.Dynamic.global.___global.require("lib/swoval_apple_file_system.node")
    catch {
      case _: Exception =>
        js.Dynamic.global.___global.require("./lib/swoval_apple_file_system.node")
    }

  def close(handle: Double): Unit = if (!closed) {
    fe.close(handle)
    closed = true
  }

  def init(consumer: js.Function2[String, Int, Unit],
           pathConsumer: js.Function1[String, Unit]): Double = {
    if (!closed) fe.init(consumer, pathConsumer).asInstanceOf[Double]
    else throw new IllegalStateException("Tried to call init on closed FileEventsApi")
  }

  def createStream(path: String, latency: Double, flags: Int, handle: Double): Int =
    if (!closed) fe.createStream(path, latency, flags, handle).asInstanceOf[Int]
    else throw new IllegalStateException("Tried to call createStream on closed FileEventsApi")

  def stopStream(handle: Double, streamHandle: Int): Unit = {
    if (!closed) fe.stopStream(handle, streamHandle)
    else throw new IllegalStateException("Tried to call stopStream on closed FileEventsApi")
  }
}
