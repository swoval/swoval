package com.swoval

import java.io.{ File, FileFilter, IOException }
import java.nio.charset.Charset
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, NoSuchFileException, Path }

import com.swoval.files.Directory._
import com.swoval.functional.Consumer
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._

/**
 * Provides helper functions to make it more convenient to test the classes in com.swoval.files. It
 * provides numerous implicit classes to convert scala functions to java functional interfaces.
 * These conversions are to support scala 2.10 and scala 2.11 without the experimental compiler
 * flag set.
 *
 */
package object files extends PlatformFiles {
  val Ignore = Observers.apply(new OnChange[Path] {
    override def apply(cacheEntry: Entry[Path]): Unit = {}
  })
  implicit class ConverterFunctionOps[T](val f: Path => T) extends Converter[T] {
    override def apply(path: Path): T = f(path)
  }
  implicit class FileFilterFunctionOps(val f: File => Boolean) extends FileFilter {
    override def accept(pathname: File): Boolean = f(pathname)
  }
  implicit class EntryFilterFunctionOps[T](val f: Entry[T] => Boolean) extends EntryFilter[T] {
    override def accept(cacheEntry: Entry[_ <: T]): Boolean =
      f(Entries.valid(cacheEntry.getPath, cacheEntry.getKind, cacheEntry.getValue))
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
