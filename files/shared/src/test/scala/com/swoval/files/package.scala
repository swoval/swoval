package com.swoval
package files

import java.io.{ File, FileFilter, IOException }
import java.nio.file.{ Path, Paths }

import com.swoval.files.FileTreeDataViews.{ CacheObserver, Converter, Entry }
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.test.platform.Bool
import com.swoval.functional.{ Consumer, Filter }
import com.swoval.runtime.Platform
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

  val baseDir: Path = Paths.get("").toAbsolutePath
  val targetDir: Path = baseDir.getFileName.toString match {
    case "js" | "jvm" => baseDir.resolve("target")
    case _ =>
      baseDir.resolve("files").resolve(if (Platform.isJVM) "jvm" else "js").resolve("target")
  }

  val Ignore: CacheObserver[_] = getObserver[Path]((_: Entry[Path]) => {})

  def getObserver[T <: AnyRef](
      oncreate: Entry[T] => Unit,
      onupdate: (Entry[T], Entry[T]) => Unit,
      ondelete: Entry[T] => Unit,
      onerror: IOException => Unit = _ => {}): FileTreeDataViews.CacheObserver[T] =
    new FileTreeDataViews.CacheObserver[T] {
      override def onCreate(newEntry: Entry[T]): Unit = oncreate(newEntry)

      override def onDelete(oldEntry: Entry[T]): Unit = ondelete(oldEntry)

      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit =
        onupdate(oldEntry, newEntry)

      override def onError(exception: IOException): Unit = onerror(exception)
    }

  def getObserver[T <: AnyRef](onUpdate: Entry[T] => Unit): FileTreeDataViews.CacheObserver[T] =
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

  implicit class RunableOps(val f: () => _) extends Runnable {
    override def run(): Unit = f()
  }

  implicit class EntryFilterFunctionOps[T](val f: Entry[T] => Boolean) extends Filter[Entry[T]] {
    override def accept(cacheEntry: Entry[T]): Boolean = f(cacheEntry)
  }

  implicit class CallbackOps(f: PathWatchers.Event => _) extends Observer[PathWatchers.Event] {
    override def onError(t: Throwable): Unit = {}
    override def onNext(t: PathWatchers.Event): Unit = f(t)
  }
  implicit class CacheObserverFunctionOps[T](val f: Entry[T] => Unit)
      extends FileTreeDataViews.CacheObserver[T] {
    override def onCreate(newCachedPath: Entry[T]): Unit = f(newCachedPath)

    override def onDelete(oldCachedPath: Entry[T]): Unit = f(oldCachedPath)

    override def onUpdate(oldCachedPath: Entry[T], newCachedPath: Entry[T]): Unit =
      f(newCachedPath)

    override def onError(exception: IOException): Unit = {}
  }

  implicit class ConsumerFunctionOps[T](val f: T => Unit) extends Consumer[T] {
    override def accept(t: T): Unit = f(t)
  }

  implicit class SeqPathOps[T](val l: Seq[Path]) extends AnyVal {
    def ===(r: Seq[Path]): Unit = new RichTraversable(l) === r

    def ===(r: Set[Path]): Unit = new RichTraversable(l.toSet) === r
  }

  object EntryOps {

    implicit class SeqEntryOps[T](val l: Seq[Entry[T]]) extends AnyVal {
      def ===(r: Seq[Path]): Unit = new RichTraversable(l.map(_.getTypedPath.getPath)) === r

      def ===(r: Set[Path]): Unit = new RichTraversable(l.map(_.getTypedPath.getPath).toSet) === r
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
