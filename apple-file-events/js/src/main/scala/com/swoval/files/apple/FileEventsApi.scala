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
  @JSExport("apply")
  def apply(consumer: FileEvent => Unit, pathConsumer: String => Unit): FileEventsApi = {
    val jsConsumer: js.Function2[String, Int, Unit] = (s: String, i: Int) =>
      consumer(new FileEvent(s, i))
    val jsPathConsumer: js.Function1[String, Unit] = (s: String) => pathConsumer(s)
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
           pathConsumer: js.Function1[String, Unit]): Double =
    fe.init(consumer, pathConsumer).asInstanceOf[Double]

  def createStream(path: String, latency: Double, flags: Int, handle: Double): Int =
    fe.createStream(path, latency, flags, handle).asInstanceOf[Int]

  def stopStream(handle: Double, streamHandle: Int): Unit = fe.stopStream(handle, streamHandle)
}
