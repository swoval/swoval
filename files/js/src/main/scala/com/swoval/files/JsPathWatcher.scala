package com.swoval.files

import java.nio.file.Paths

import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatchers.Event

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("com.swoval.files.PathWatcher")
class JsPathWatcher(callback: js.UndefOr[js.Function2[String, String, Unit]]) extends js.Object {
  private[this] val callbacks = new Observers[Event]()
  callback.toOption.foreach(addCallback)
  private[this] val inner: PathWatcher[PathWatchers.Event] = {
    val res = PathWatchers.get()
    res.addObserver(callbacks)
    res
  }
  def close(): Unit = inner.close()
  def register(path: String, recursive: Boolean = true): Unit =
    inner.register(Paths.get(path), if (recursive) Integer.MAX_VALUE else 0)
  def addCallback(callback: js.Function2[String, String, Unit]): Int =
    callbacks.addObserver(new Observer[Event] {
      override def onError(t: Throwable): Unit = {}
      override def onNext(event: Event): Unit =
        callback.apply(event.getPath().toString, event.getKind().toString)
    })
  def removeCallback(handle: Int): Unit = callbacks.removeObserver(handle)
}
