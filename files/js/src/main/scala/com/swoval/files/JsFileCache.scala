package com.swoval.files

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("FileCache")
class JsFileCache(callback: js.UndefOr[js.Function2[String, String, Unit]]) extends js.Object {
  private[this] val inner = new FileCacheImpl(Options.default)
  callback.toOption.foreach(addCallback)
  def list(path: String,
           recursive: js.UndefOr[Boolean],
           filter: js.UndefOr[js.Function1[String, Boolean]]): js.Array[String] = {
    inner
      .list(Path(path),
            recursive.toOption.getOrElse(false),
            (p: Path) => filter.toOption.forall(_.apply(p.fullName)))
      .map(_.fullName)
      .toJSArray
  }
  def addCallback(callback: js.Function2[String, String, Unit]): Int =
    inner.addCallback(fe => callback.apply(fe.path.fullName, fe.kind.toString))
  def removeCallback(handle: Int): Unit = inner.removeCallback(handle)
  def close(): Unit = inner.close()
}
