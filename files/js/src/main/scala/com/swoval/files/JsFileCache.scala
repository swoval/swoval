package com
package swoval
package files

import java.nio.file.Paths

import com.swoval.files.FileTreeDataViews.{ Converter, Entry }
import com.swoval.functional.Filter

import scala.collection.JavaConverters._
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.{ JSExport, JSExportTopLevel }

private object EitherOpsHolder {
  implicit class EitherOps[L, R](val e: functional.Either[L, R]) {
    def getOrElse[U >: R](u: R): U = functional.Either.getOrElse(e, u)
  }
}
import com.swoval.files.EitherOpsHolder._
@JSExportTopLevel("FileCache")
class JsFileCache[T <: AnyRef](converter: js.UndefOr[js.Function1[TypedPath, T]],
                               callback: js.UndefOr[js.Function1[JSEntry[T], Unit]])
    extends js.Object {
  private[this] val inner: FileTreeRepository[T] = FileTreeRepositories.get(
    converter.toOption
      .map(c =>
        new Converter[T] {
          override def apply(path: TypedPath): T = c(path)
      })
      .getOrElse(new Converter[T] {
        override def apply(path: TypedPath): T = path.asInstanceOf[T]
      }))
  callback.toOption.foreach(addCallback)
  def list(path: String,
           recursive: js.UndefOr[Boolean],
           filter: js.UndefOr[js.Function1[JSEntry[T], Boolean]]): js.Array[JSEntry[T]] = {
    inner
      .listEntries(
        Paths.get(path),
        if (recursive.toOption.getOrElse(false)) Integer.MAX_VALUE else 0,
        new Filter[Entry[T]] {
          override def accept(entry: Entry[T]): Boolean =
            filter.fold(true)(_.apply(
              new JSEntry[T](entry.getPath().toString,
                             functional.Either.getOrElse(entry.getValue(), null.asInstanceOf[T]))))
        }
      )
      .asScala
      .map(e =>
        new JSEntry[T](e.getPath().toString, e.getValue().getOrElse[T](null.asInstanceOf[T])))
      .toJSArray
  }
  def register(path: String,
               recursive: js.UndefOr[Boolean],
               filter: js.UndefOr[js.Function1[String, Boolean]]): Unit = {
    inner.register(Paths.get(path),
                   if (recursive.toOption.getOrElse(false)) Integer.MAX_VALUE else 0)
  }
  def addCallback(callback: js.Function1[JSEntry[T], Unit]): Int =
    inner.addObserver(new FileTreeViews.Observer[Entry[T]] {
      override def onError(t: Throwable): Unit = {}
      override def onNext(t: Entry[T]): Unit =
        callback(
          new JSEntry[T](t.getPath().toString,
                         functional.Either.getOrElse(t.getValue(), null.asInstanceOf[T])))
    })
  def removeCallback(handle: Int): Unit = inner.removeObserver(handle)
  def close(): Unit = inner.close()
}

@JSExportTopLevel("Entry")
class JSEntry[T](@JSExport("path") val path: String, @JSExport("value") val value: T)
