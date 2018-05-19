package com.swoval

import java.io.{ File, FileFilter }
import java.nio.charset.Charset
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, NoSuchFileException, Path }

import com.swoval.files.AppleDirectoryWatcher.OnStreamRemoved
import com.swoval.files.Directory._
import com.swoval.files.DirectoryWatcher.Callback
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
      f(new Entry[T](cacheEntry.path, cacheEntry.value, cacheEntry.isDirectory))
  }
  implicit class OnChangeFunctionOps[T](val f: Entry[T] => Unit) extends OnChange[T] {
    override def apply(cacheEntry: Entry[T]): Unit = f(cacheEntry)
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
  }
  implicit class CallbackFunctionOps(val f: DirectoryWatcher.Event => Unit) extends Callback {
    override def apply(fileWatchEvent: DirectoryWatcher.Event): Unit = f(fileWatchEvent)
  }
  implicit class OnStreamRemovedFunctionOps[T](val f: String => Unit) extends OnStreamRemoved {
    override def apply(s: String): Unit = f(s)
  }
  implicit class SeqPathOps[T](val l: Seq[Path]) extends AnyVal {
    def ===(r: Seq[Path]): Unit = new RichTraversable(l) === r
    def ===(r: Set[Path]): Unit = new RichTraversable(l.toSet) === r
  }
  object EntryOps {
    implicit class SeqEntryOps[T](val l: Seq[Entry[T]]) extends AnyVal {
      def ===(r: Seq[Path]): Unit = new RichTraversable(l.map(_.path)) === r
      def ===(r: Set[Path]): Unit = new RichTraversable(l.map(_.path).toSet) === r
    }
  }
  implicit class PathOps(val path: Path) {
    def getBytes: Array[Byte] = Files.readAllBytes(path)
    def createFile(): Path = Files.createFile(path)
    def delete(): Boolean = Files.deleteIfExists(path)
    def deleteRecursive(): Unit = {
      if (Files.isDirectory(path)) {
        try FileOps
          .list(path, true)
          .asScala
          .foreach(p => new PathOps(p.toPath).deleteRecursive())
        catch { case _: NoSuchFileException => }
      }
      Files.deleteIfExists(path)
    }
    def exists: Boolean = Files.exists(path)
    def isDirectory: Boolean = Files.isDirectory(path)
    def lastModified: Long = Files.getLastModifiedTime(path).toMillis
    def list(recursive: Boolean, filter: FileFilter = FileOps.AllPass): Seq[Path] =
      FileOps.list(path, recursive, filter).asScala.map(_.toPath)
    def mkdir(): Path = Files.createDirectory(path)
    def mkdirs(): Path = Files.createDirectories(path)
    def name: String = path.getFileName.toString
    def renameTo(target: Path): Path = Files.move(path, target)
    def parts: Seq[Path] = FileOps.parts(path).asScala.toIndexedSeq
    def setLastModifiedTime(lastModified: Long): Unit =
      Files.setLastModifiedTime(path, FileTime.fromMillis(lastModified))
    def write(bytes: Array[Byte]): Path = Files.write(path, bytes)
    def write(content: String, charset: Charset = Charset.defaultCharset()): Path =
      Files.write(path, content.getBytes(charset))
  }

  implicit class TestPathOps(val path: java.nio.file.Path) extends AnyVal {
    def ===(other: java.nio.file.Path): Unit = {
      if (path.normalize != other.normalize) {
        path.normalize ==> other.normalize
      }
    }
  }
}
