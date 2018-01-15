package com.swoval.files.apple

import scala.scalajs.js
import scala.scalajs.js.annotation.{ JSExport, JSExportAll, JSExportTopLevel, JSImport }

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
    val jsConsumer: js.Function2[String, Int, Unit] = (s, i) => consumer(new FileEvent(s, i))
    val jsPathConsumer: js.Function1[String, Unit] = s => pathConsumer(s)
    new FileEventsApi(FileEventsApiFacade.init(jsConsumer, jsPathConsumer))
  }
}

@js.native
@JSImport("swoval_apfs", JSImport.Default)
private object FileEventsApiFacade extends js.Object {
  def close(handle: Double): Unit = js.native

  def init(consumer: js.Function2[String, Int, Unit],
           pathConsumer: js.Function1[String, Unit]): Double = js.native

  def createStream(path: String, latency: Double, flags: Int, handle: Double): Int = js.native

  def stopStream(handle: Double, streamHandle: Int): Unit = js.native
}
