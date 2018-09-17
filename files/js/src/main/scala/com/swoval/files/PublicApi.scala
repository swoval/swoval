package com.swoval
package files

import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import com.swoval.files.Converters._
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.functional.Filter

import scala.collection.JavaConverters._
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.UndefOr
import scala.scalajs.js.annotation.{ JSExport, JSExportAll, JSExportTopLevel }

@JSExportTopLevel("FileTreeRepositories")
object JSFileTreeRepositories {
  @JSExport
  def get[T <: AnyRef](converter: js.UndefOr[js.Function1[JSTypedPath, T]],
                       followLinks: js.UndefOr[Boolean]): JSFileTreeRepository[T] = {
    val underlying =
      FileTreeRepositories.get(
        followLinks.toOption.getOrElse(true),
        new Converter[T] {
          val function = converter.toOption match {
            case Some(f) =>
              (typedPath: TypedPath) =>
                f(new JSTypedPath(typedPath))
            case None =>
              (typedPath: TypedPath) =>
                new JSTypedPath(typedPath).asInstanceOf[T]
          }
          override def apply(typedPath: TypedPath): T = function(typedPath)
        }
      )
    new JSFileTreeRepository[T](underlying)
  }
}

@JSExportTopLevel("FileTreeRepository")
@JSExportAll
class JSFileTreeRepository[T <: AnyRef](private[this] val underlying: FileTreeRepository[T]) {
  def close(): Unit = underlying.close()
  def addObserver(observer: JSObserver[JSFileTreeDataViews.Entry[T]]): Int =
    underlying.addObserver(observer.toSwoval((e: FileTreeDataViews.Entry[T]) => e.toJS))
  def addCacheObserver(cacheObserver: JSCacheObserver[T]): Int =
    underlying.addCacheObserver(cacheObserver.toSwoval)
  def register(path: String, maxDepth: js.UndefOr[Int]): Either[IOException, Boolean] =
    underlying.register(Paths.get(path), maxDepth.getOrElse(Integer.MAX_VALUE)).asScala
  def removeObserver(handle: Int): Unit = underlying.removeObserver(handle)
  def unregister(path: String): Unit = underlying.unregister(Paths.get(path))
  def list(path: String,
           maxDepth: UndefOr[Int],
           filter: UndefOr[js.Function1[JSTypedPath, Boolean]]): js.Array[JSTypedPath] = {
    val jsFilter: Filter[TypedPath] = new Filter[TypedPath] {
      val f = filter.toOption match {
        case Some(f) =>
          (typedPath: TypedPath) =>
            f(new JSTypedPath(typedPath))
        case _ =>
          (_: TypedPath) =>
            true
      }
      override def accept(typedPath: TypedPath): Boolean = f(typedPath)
    }
    underlying
      .list(Paths.get(path), maxDepth.toOption.getOrElse(Integer.MAX_VALUE), jsFilter)
      .asScala
      .view
      .map(new JSTypedPath(_))
      .toJSArray
  }
  def listEntries(path: String,
                  maxDepth: UndefOr[Int],
                  filter: UndefOr[js.Function1[JSFileTreeDataViews.Entry[T], Boolean]])
    : js.Array[JSFileTreeDataViews.Entry[T]] = {
    val jsFilter: Filter[FileTreeDataViews.Entry[T]] =
      new Filter[FileTreeDataViews.Entry[T]] {
        val f = filter.toOption match {
          case Some(f) =>
            (entry: FileTreeDataViews.Entry[T]) =>
              f(entry.toJS)
          case _ =>
            (_: FileTreeDataViews.Entry[T]) =>
              true
        }
        override def accept(entry: FileTreeDataViews.Entry[T]): Boolean = f(entry)
      }
    underlying
      .listEntries(Paths.get(path), maxDepth.toOption.getOrElse(Integer.MAX_VALUE), jsFilter)
      .asScala
      .view
      .map(_.toJS)
      .toJSArray
  }
}

@JSExportTopLevel("PathWatchers")
@JSExportAll
object JSPathWatchers {
  def get(followLinks: js.UndefOr[Boolean]): JSPathWatcher =
    new JSPathWatcher(PathWatchers.get(followLinks.toOption.getOrElse(true)))
  def polling(followLinks: js.UndefOr[Boolean], intervalMS: js.UndefOr[Double]): JSPathWatcher = {
    new JSPathWatcher(
      PathWatchers.polling(followLinks.toOption.getOrElse(true),
                           intervalMS.toOption.getOrElse(500.0).toLong,
                           TimeUnit.MILLISECONDS))
  }
  @JSExportAll
  case class Event(getTypedPath: JSTypedPath, getKind: String)
}

@JSExportTopLevel("PathWatcher")
@JSExportAll
class JSPathWatcher(val underlying: PathWatcher[PathWatchers.Event]) {
  def register(path: String, maxDepth: Int): JSEither[IOException, Boolean] =
    new JSEither(underlying.register(Paths.get(path), maxDepth))
  def unregister(path: String): Unit = underlying.unregister(Paths.get(path))
  def close(): Unit = underlying.close()
  def addObserver(observer: JSObserver[JSPathWatchers.Event]): Int =
    underlying.addObserver(observer.toSwoval(_.toJS))
  def removeObserver(handle: Int): Unit = underlying.removeObserver(handle)
}

@JSExportTopLevel("Observer")
@JSExportAll
class JSObserver[T <: AnyRef](val onNext: js.Function1[T, Unit],
                              val onError: js.UndefOr[js.Function1[Throwable, Unit]])

@JSExportTopLevel("CacheObserver")
@JSExportAll
class JSCacheObserver[T](
    val onCreate: js.Function1[JSFileTreeDataViews.Entry[T], Unit],
    val onDelete: js.Function1[JSFileTreeDataViews.Entry[T], Unit],
    val onUpdate: js.Function2[JSFileTreeDataViews.Entry[T], JSFileTreeDataViews.Entry[T], Unit],
    val onError: js.UndefOr[js.Function1[IOException, Unit]]
)

@JSExportTopLevel("CacheObservers")
@JSExportAll
object JSCacheObservers {
  def get[T](func: js.Function1[JSFileTreeDataViews.Entry[T], Unit],
             onError: js.UndefOr[js.Function1[IOException, Unit]]): JSCacheObserver[T] =
    new JSCacheObserver[T](
      func,
      func,
      ((_: JSFileTreeDataViews.Entry[T], e: JSFileTreeDataViews.Entry[T]) => func(e)): js.Function2[
        JSFileTreeDataViews.Entry[T],
        JSFileTreeDataViews.Entry[T],
        Unit],
      onError
    )
}

@JSExportTopLevel("FileTreeDataViews")
@JSExportAll
object JSFileTreeDataViews {
  @JSExportAll
  case class Entry[T](getTypedPath: JSTypedPath, getValue: JSEither[IOException, T])
}

@JSExportTopLevel("Either")
@JSExportAll
class JSEither[L, R](private[this] val either: functional.Either[L, R]) {
  def left: JSLeftProjection[L, R] =
    new JSLeftProjection[L, R](functional.Either.leftProjection(either))
  def isLeft: Boolean = either.isLeft()
  def isRight: Boolean = either.isRight()
  def getOrElse[R0 >: R](r: R0): R0 = functional.Either.getOrElse(either, r)
  def right: JSRightProjection[L, R] =
    new JSRightProjection[L, R](functional.Either.rightProjection(either))
  override def hashCode(): Int = either.hashCode()
  override def equals(other: Any): Boolean = either.equals(other)
}
@JSExportAll
class JSLeftProjection[+L, +R](private[this] val left: functional.Either.Left[L, R]) {
  def getValue: L = left.getValue
  def isLeft: Boolean = left.isLeft()
  def isRight: Boolean = left.isRight()
  override def toString(): String = left.toString()
  override def equals(other: Any): Boolean = left.equals(other)
  override def hashCode(): Int = left.hashCode()
  def get(): R = left.get()
}
@JSExportAll
class JSRightProjection[+L, +R](private[this] val right: functional.Either.Right[L, R]) {
  def getValue: R = right.getValue
  def isLeft: Boolean = right.isLeft()
  def isRight: Boolean = right.isRight()
  override def toString: String = right.toString()
  override def equals(other: Any): Boolean = right.equals(other)
  override def hashCode(): Int = right.hashCode()
  def get(): R = right.get()
}

@JSExportTopLevel("TypedPath")
@JSExportAll
class JSTypedPath(private[this] val typedPath: TypedPath) {
  def getPath: String = typedPath.getPath.toString
  def exists: Boolean = typedPath.exists
  def isDirectory: Boolean = typedPath.isDirectory
  def isFile: Boolean = typedPath.isFile
  def isSymbolicLink: Boolean = typedPath.isSymbolicLink
  def toRealPath = typedPath.toRealPath.toString
  override def toString = s"TypedPath($getPath)"
}

private[files] object Converters {
  implicit class SwovalEitherOps[L, R](val either: functional.Either[L, R]) extends AnyVal {
    def asScala: Either[L, R] =
      if (either.isRight) Right(either.get)
      else Left(functional.Either.leftProjection(either).getValue)
    def asJS: JSEither[L, R] = new JSEither(either)
  }
  implicit class EitherOps[L, R](val either: Either[L, R]) extends AnyVal {
    def asSwoval: functional.Either[L, R] =
      if (either.isRight) functional.Either.right(either.right.get)
      else functional.Either.left(either.left.get)
    def asJS: JSEither[L, R] = new JSEither(either.asSwoval)
  }
  implicit class JSEitherOps[L, R](val either: JSEither[L, R]) extends AnyVal {
    def asScala: Either[L, R] =
      if (either.isRight) Right(either.right.get) else Left(either.left.getValue)
  }
  implicit class EntryOps[T](val entry: FileTreeDataViews.Entry[T]) extends AnyVal {
    def toJS: JSFileTreeDataViews.Entry[T] =
      JSFileTreeDataViews.Entry(entry.getTypedPath.toJS, entry.getValue.asJS)
  }
  implicit class TypedPathOps(val typedPath: TypedPath) extends AnyVal {
    def toJS: JSTypedPath = new JSTypedPath(typedPath)
  }
  implicit class JSObserverOps[T <: AnyRef](val observer: JSObserver[T]) extends AnyVal {
    def toSwoval[U](f: U => T): FileTreeViews.Observer[U] =
      new FileTreeViews.Observer[U] {
        override def onError(t: Throwable): Unit = observer.onError.foreach(_(t))
        override def onNext(u: U): Unit = observer.onNext(f(u))
      }
  }
  implicit class JSCacheObserverOps[T <: AnyRef](val observer: JSCacheObserver[T]) extends AnyVal {
    def toSwoval: FileTreeDataViews.CacheObserver[T] = new FileTreeDataViews.CacheObserver[T] {
      override def onError(ioException: IOException): Unit =
        observer.onError.foreach(_(ioException))
      override def onCreate(newEntry: FileTreeDataViews.Entry[T]): Unit =
        observer.onCreate(newEntry.toJS)
      override def onDelete(oldEntry: FileTreeDataViews.Entry[T]): Unit =
        observer.onDelete(oldEntry.toJS)
      override def onUpdate(oldEntry: FileTreeDataViews.Entry[T],
                            newEntry: FileTreeDataViews.Entry[T]): Unit =
        observer.onUpdate(oldEntry.toJS, newEntry.toJS)
    }
  }
  implicit class EventOps(val event: PathWatchers.Event) extends AnyVal {
    def toJS: JSPathWatchers.Event =
      new JSPathWatchers.Event(event.typedPath.toJS, event.kind.toString)
  }
}
