package com.swoval.files

import com.swoval.files.apple.Flags

import scala.concurrent.duration._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.Properties

@JSExportTopLevel("com.swoval.files.DirectoryWatcher")
class JsDirectoryWatcher(callback: js.UndefOr[js.Function2[String, String, Unit]])
    extends js.Object {
  private[this] val callbacks = Callbacks()
  callback.toOption.foreach(addCallback)
  private[this] val inner: DirectoryWatcher = if (Properties.isMac) {
    AppleDirectoryWatcher(10.milliseconds, new Flags.Create().setFileEvents.setNoDefer)(
      callbacks.callback)
  } else {
    new NioDirectoryWatcher(callbacks.callback)
  }
  def close(): Unit = inner.close()
  def register(path: String, recursive: Boolean = true): Unit =
    inner.register(Path(path), recursive)
  def addCallback(callback: js.Function2[String, String, Unit]): Int =
    callbacks.addCallback(fe => callback.apply(fe.path.fullName, fe.kind.toString))
  def removeCallback(handle: Int): Unit = callbacks.removeCallback(handle)
}
