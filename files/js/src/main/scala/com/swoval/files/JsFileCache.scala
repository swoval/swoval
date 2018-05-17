package com.swoval.files

import java.nio.file.{ Path, Paths }

import com.swoval.files.Directory.{ Converter, Entry, EntryFilter, OnChange }

import scala.collection.JavaConverters._
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.{ JSExport, JSExportTopLevel }

@JSExportTopLevel("FileCache")
class JsFileCache[T](converter: js.UndefOr[js.Function1[Path, T]],
                     callback: js.UndefOr[js.Function1[JSEntry[T], Unit]])
    extends js.Object {
  private[this] val inner: FileCache[T] = FileCache.apply(
    converter.toOption
      .map(c =>
        new Converter[T] {
          override def apply(path: Path): T = c(path)
      })
      .getOrElse(new Converter[T] {
        override def apply(path: Path): T = path.asInstanceOf[T]
      }))
  callback.toOption.foreach(addCallback)
  def list(path: String,
           recursive: js.UndefOr[Boolean],
           filter: js.UndefOr[js.Function1[JSEntry[T], Boolean]]): js.Array[JSEntry[T]] = {
    inner
      .list(
        Paths.get(path),
        recursive.toOption.getOrElse(false),
        new EntryFilter[T] {
          override def accept(entry: Entry[_ <: T]): Boolean =
            filter.fold(true)(_.apply(new JSEntry[T](entry.path.toString, entry.value)))
        }
      )
      .asScala
      .map(e => new JSEntry[T](e.path.toString, e.value))
      .toJSArray
  }
  def register(path: String,
               recursive: js.UndefOr[Boolean],
               filter: js.UndefOr[js.Function1[String, Boolean]]): Unit = {
    inner.register(Paths.get(path), recursive.toOption.getOrElse(false))
  }
  def addCallback(callback: js.Function1[JSEntry[T], Unit]): Int =
    inner.addCallback(new OnChange[T] {
      override def apply(entry: Entry[T]) =
        callback.apply(new JSEntry[T](entry.path.toString, entry.value))
    })
  def removeCallback(handle: Int): Unit = inner.removeObserver(handle)
  def close(): Unit = inner.close()
}

@JSExportTopLevel("Entry")
class JSEntry[T](@JSExport("path") val path: String, @JSExport("value") val value: T)
