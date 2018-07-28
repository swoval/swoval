package com.swoval
package files

import java.io.{ File, FileFilter, IOException }
import java.nio.file.Path

import com.swoval.files.FileTreeDataViews.{ Converter, Entry, OnError }
import com.swoval.files.FileTreeViews.{ CacheObserver, Observer }
import com.swoval.files.test.platform.Bool
import com.swoval.functional.{ Consumer, Filter }
import com.swoval.test._
import utest._

/**
 * Provides helper functions to make it more convenient to test the classes in com.swoval.files. It
 * provides numerous implicit classes to convert scala functions to java functional interfaces.
 * These conversions are to support scala 2.10 and scala 2.11 without the experimental compiler
 * flag set.
 *
 */
object TestHelpers extends PlatformFiles {
  val Ignore: CacheObserver[_] = getObserver[Path]((_: Entry[Path]) => {})

  def getObserver[T <: AnyRef](
      oncreate: Entry[T] => Unit,
      onupdate: (Entry[T], Entry[T]) => Unit,
      ondelete: Entry[T] => Unit,
      onerror: IOException => Unit = _ => {}): FileTreeViews.CacheObserver[T] =
    new FileTreeViews.CacheObserver[T] {
      override def onCreate(newEntry: Entry[T]): Unit = oncreate(newEntry)

      override def onDelete(oldEntry: Entry[T]): Unit = ondelete(oldEntry)

      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit =
        onupdate(oldEntry, newEntry)

      override def onError(exception: IOException): Unit = onerror(exception)
    }

  def getObserver[T <: AnyRef](onUpdate: Entry[T] => Unit): FileTreeViews.CacheObserver[T] =
    getObserver[T](onUpdate, (_: Entry[T], e: Entry[T]) => onUpdate(e), onUpdate)

  implicit class PathWatcherOps[T](val watcher: PathWatcher[T]) extends AnyVal {
    def register(path: Path, recursive: Boolean): functional.Either[IOException, Bool] =
      watcher.register(path, if (recursive) Integer.MAX_VALUE else 0)

    def register(path: Path): functional.Either[IOException, Bool] =
      register(path, recursive = true)
  }

  implicit class EitherOps[L, R](val either: functional.Either[L, R]) extends AnyVal {
    def getOrElse[U >: R](u: U): U = functional.Either.getOrElse(either, u)

    def left(): functional.Either.Left[L, R] = functional.Either.leftProjection[L, R](either)

    def right(): functional.Either.Right[L, R] = functional.Either.rightProjection[L, R](either)
  }

  implicit class ConverterFunctionOps[T](val f: TypedPath => T) extends Converter[T] {
    override def apply(path: TypedPath): T = f(path)
  }

  implicit class FileFilterFunctionOps(val f: File => Boolean) extends FileFilter {
    override def accept(pathname: File): Boolean = f(pathname)
  }

  implicit class FilterOps[T](val f: T => Boolean) extends functional.Filter[T] {
    override def accept(t: T): Boolean = f(t)
  }

  implicit class EntryOps[T](val entry: Entry[T]) {
    def value: T = entry.getValue.get
  }

  implicit class FunctionOps[T, R](val f: T => R) extends com.swoval.files.Function[T, R] {
    override def apply(t: T): R = f(t)
  }

  implicit class EntryFilterFunctionOps[T](val f: Entry[T] => Boolean) extends Filter[Entry[T]] {
    override def accept(cacheEntry: Entry[T]): Boolean = f(cacheEntry)
  }

  implicit class OnErrorFunctionOps(val f: IOException => Unit) extends OnError {
    override def apply(exception: IOException): Unit = f(exception)
  }

  implicit class CallbackOps(f: PathWatchers.Event => _) extends Observer[PathWatchers.Event] {
    override def onError(t: Throwable): Unit = {}
    override def onNext(t: PathWatchers.Event): Unit = f(t)
  }
  implicit class CacheObserverFunctionOps[T](val f: Entry[T] => Unit)
      extends FileTreeViews.CacheObserver[T] {
    override def onCreate(newCachedPath: Entry[T]): Unit = f(newCachedPath)

    override def onDelete(oldCachedPath: Entry[T]): Unit = f(oldCachedPath)

    override def onUpdate(oldCachedPath: Entry[T], newCachedPath: Entry[T]): Unit =
      f(newCachedPath)

    override def onError(exception: IOException): Unit = {}
  }

  implicit class ConsumerFunctionOps[T](val f: T => Unit) extends Consumer[T] {
    override def accept(t: T): Unit = f(t)
  }

  implicit class BiConsumerFunctionOps[T, U](val f: (T, U) => Unit) extends BiConsumer[T, U] {
    override def accept(t: T, u: U): Unit = f(t, u)
  }
  implicit class SeqPathOps[T](val l: Seq[Path]) extends AnyVal {
    def ===(r: Seq[Path]): Unit = new RichTraversable(l) === r

    def ===(r: Set[Path]): Unit = new RichTraversable(l.toSet) === r
  }

  object EntryOps {

    implicit class SeqEntryOps[T](val l: Seq[Entry[T]]) extends AnyVal {
      def ===(r: Seq[Path]): Unit = new RichTraversable(l.map(_.getPath)) === r

      def ===(r: Set[Path]): Unit = new RichTraversable(l.map(_.getPath).toSet) === r
    }

  }

  implicit class TestPathOps(val path: java.nio.file.Path) extends AnyVal {
    def ===(other: java.nio.file.Path): Unit = {
      if (path.normalize != other.normalize) {
        path.normalize ==> other.normalize
      }
    }
  }

}
