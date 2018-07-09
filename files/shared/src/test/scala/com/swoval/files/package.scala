package com.swoval

import java.io.{ File, FileFilter, IOException }
import java.nio.file.Path

import com.swoval.files.Directory._
import com.swoval.functional.Consumer
import com.swoval.test._
import utest._

/**
 * Provides helper functions to make it more convenient to test the classes in com.swoval.files. It
 * provides numerous implicit classes to convert scala functions to java functional interfaces.
 * These conversions are to support scala 2.10 and scala 2.11 without the experimental compiler
 * flag set.
 *
 */
package object files extends PlatformFiles {
  val Ignore = getObserver[Path]((_: Entry[Path]) => {})
  def getObserver[T](oncreate: Entry[T] => Unit,
                     onupdate: (Entry[T], Entry[T]) => Unit,
                     ondelete: Entry[T] => Unit,
                     onerror: (Path, IOException) => Unit = (_, _) => {}): Observer[T] =
    new Observer[T] {
      override def onCreate(newEntry: Entry[T]): Unit = oncreate(newEntry)
      override def onDelete(oldEntry: Entry[T]): Unit = ondelete(oldEntry)
      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit =
        onupdate(oldEntry, newEntry)
      override def onError(path: Path, exception: IOException): Unit = {
        onerror(path, exception)
      }
    }
  def getObserver[T](onUpdate: Entry[T] => Unit): Observer[T] =
    getObserver[T](onUpdate, (_: Entry[T], e: Entry[T]) => onUpdate(e), onUpdate)

  implicit class EitherOps[L, R](val either: functional.Either[L, R]) extends AnyVal {
    def getOrElse[U >: R](u: U): U = functional.Either.getOrElse(either, u)
    def left(): functional.Either.Left[L, R] = functional.Either.leftProjection[L, R](either)
    def right(): functional.Either.Right[L, R] = functional.Either.rightProjection[L, R](either)
  }
  implicit class ConverterFunctionOps[T](val f: Path => T) extends Converter[T] {
    override def apply(path: Path): T = f(path)
  }
  implicit class FileFilterFunctionOps(val f: File => Boolean) extends FileFilter {
    override def accept(pathname: File): Boolean = f(pathname)
  }
  implicit class EntryOps[T](val entry: Entry[T]) {
    def value: T = entry.getValue.get
  }
  implicit class EntryFilterFunctionOps[T](val f: Entry[T] => Boolean) extends EntryFilter[T] {
    override def accept(cacheEntry: Entry[_ <: T]): Boolean =
      f(cacheEntry.asInstanceOf[Entry[T]])
  }
  implicit class OnChangeFunctionOps[T](val f: Entry[T] => Unit) extends OnChange[T] {
    override def apply(cacheEntry: Entry[T]): Unit = f(cacheEntry)
  }
  implicit class OnErrorFunctionOps(val f: (Path, IOException) => Unit) extends OnError {
    override def apply(path: Path, exception: IOException): Unit = f(path, exception)
  }
  implicit class OnUpdateFunctionOps[T](val f: (Entry[T], Entry[T]) => Unit) extends OnUpdate[T] {
    override def apply(oldCachedPath: Entry[T], newCachedPath: Entry[T]): Unit =
      f(oldCachedPath, newCachedPath)
  }
  implicit class ObserverFunctionOps[T](val f: Entry[T] => Unit) extends Observer[T] {
    override def onCreate(newCachedPath: Entry[T]): Unit = f(newCachedPath)
    override def onDelete(oldCachedPath: Entry[T]): Unit = f(oldCachedPath)
    override def onUpdate(oldCachedPath: Entry[T], newCachedPath: Entry[T]): Unit =
      f(newCachedPath)
    override def onError(path: Path, exception: IOException): Unit = {}
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
