package com.swoval.files.apple

import scala.scalajs.js
import scala.scalajs.js.annotation.{ JSExport, JSExportAll, JSExportTopLevel, JSImport }

@JSExportTopLevel("com.swoval.files.apple.FileSystemApi")
@JSExportAll
class FileSystemApi(handle: Double) extends AutoCloseable {
  override def close(): Unit = {
    FileSystemApiFacade.close(handle)
  }

  def createStream(path: String, latency: Double, flags: Int) = {
    FileSystemApiFacade.createStream(path, latency, flags, handle)
  }
  def stopStream(streamHandle: Int) = {
    FileSystemApiFacade.stopStream(handle, streamHandle)
  }
}

@JSExportTopLevel("com.swoval.files.apple.FileSystemApi$")
object FileSystemApi {
  @JSExport("apply")
  def apply(consumer: FileEvent => Unit, pathConsumer: String => Unit): FileSystemApi = {
    val jsConsumer: js.Function2[String, Int, Unit] = (s, i) => consumer(new FileEvent(s, i))
    val jsPathConsumer: js.Function1[String, Unit] = s => pathConsumer(s)
    new FileSystemApi(FileSystemApiFacade.init(jsConsumer, jsPathConsumer))
  }
}

@js.native
@JSImport("swoval_apfs", JSImport.Default)
private object FileSystemApiFacade extends js.Object {
  def close(handle: Double): Unit = js.native

  def init(consumer: js.Function2[String, Int, Unit],
           pathConsumer: js.Function1[String, Unit]): Double = js.native

  def createStream(path: String, latency: Double, flags: Int, handle: Double): Int = js.native

  def stopStream(handle: Double, streamHandle: Int): Unit = js.native
}
