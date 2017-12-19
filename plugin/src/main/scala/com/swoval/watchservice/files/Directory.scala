package com.swoval.watchservice.files

import java.io.{ File, FileFilter }
import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE }
import java.nio.file.{ Path, WatchEvent }

import com.swoval.watchservice.files.Directory.{ CachedFile, Callback, FileEvent }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

final case class Directory private (path: Path) {
  def add(abspath: Path, isFile: Boolean, callback: Callback): Boolean = {
    def impl(d: Directory, p: Path): Boolean = implRec(d, p.iterator.asScala.toIndexedSeq)
    @tailrec def implRec(directory: Directory, parts: Seq[Path]): Boolean = {
      parts match {
        case Seq(p) =>
          val f = CachedFile(p, isDir = false, directory.resolve(p).toFile.lastModified)
          val res = lock.synchronized(directory.files.put(p, f)).isEmpty
          if (res) callback(FileEvent(directory.resolve(p), ENTRY_CREATE))
          res
        case Seq(p, rest @ _*) =>
          lock.synchronized(directory.subdirectories get p) match {
            case None =>
              lock.synchronized {
                val dir = Directory(directory.resolve(p), callback)
                directory.subdirectories += p -> dir
                callback(FileEvent(directory.resolve(p), ENTRY_CREATE))
                dir.find(new File(rest mkString File.separator).toPath).isDefined
              }
            case Some(d) => implRec(d, rest)
          }
      }
    }
    abspath match {
      case p if !p.isAbsolute                       => impl(this, abspath)
      case p if p.isAbsolute && (p startsWith path) => impl(this, path.relativize(abspath))
      case _                                        => false
    }
  }

  def find(abspath: Path): Option[Either[File, Directory]] = {
    @tailrec def impl(dir: Directory, parts: Seq[Path]): Option[Either[File, Directory]] = {
      parts match {
        case Seq(p) =>
          lock.synchronized {
            val subdir = dir.subdirectories get p map Right.apply
            subdir orElse (dir.files get p map (cf => Left(cf.copy(f = dir.resolve(p)))))
          }
        case Seq(p, rest @ _*) =>
          lock.synchronized(dir.subdirectories get p) match {
            case Some(d) => impl(d, rest)
            case _       => None
          }
        case Seq() => None
      }
    }
    abspath match {
      case p if p == path     => Some(Right(this))
      case p if !p.isAbsolute => impl(this, p.iterator.asScala.toIndexedSeq)
      case p if p.isAbsolute && (p startsWith path) =>
        impl(this, path.relativize(p).iterator.asScala.toIndexedSeq)
      case _ => None
    }
  }

  def list(recursive: Boolean, filter: FileFilter): Seq[File] =
    lock.synchronized {
      def resolve(f: File) = path.resolve(f.toPath).toFile

      val cachedFiles = files.values
      val subdirs = subdirectories.values

      (cachedFiles.view.map(resolve).filter(filter.accept).toSeq ++
        subdirs.view.map(_.file).map(resolve).filter(filter.accept)).toIndexedSeq ++
        (if (recursive) subdirs.flatMap(_.list(recursive, filter)) else Seq.empty).toIndexedSeq
    }

  def list(path: Path, recursive: Boolean, filter: FileFilter): Seq[File] = find(path) match {
    case Some(Right(d)) => d.list(recursive, filter)
    case Some(Left(f))  => Seq(f)
    case None           => Seq.empty
  }

  def remove(abspath: Path): Boolean = {
    @tailrec def impl(dir: Directory, relPath: Path): Boolean = {
      relPath.iterator.asScala.toIndexedSeq match {
        case Seq(p) =>
          lock.synchronized(dir.files.remove(p).isDefined || dir.subdirectories.remove(p).isDefined)
        case Seq(p, rest @ _*) =>
          lock.synchronized(dir.subdirectories get p) match {
            case Some(d) => impl(d, new File(rest mkString File.separator).toPath)
            case _       => false
          }
      }
    }
    abspath match {
      case p if !p.isAbsolute                       => impl(this, abspath)
      case p if p.isAbsolute && (p startsWith path) => impl(this, path.relativize(abspath))
      case _                                        => false
    }
  }

  def traverse(callback: Callback): Directory = lock.synchronized {
    val (newDirs, newFiles) =
      if (file.exists) path.toFile.listFiles.partition(_.isDirectory) match {
        case (a, b) => a.map(relativize).toSet -> b.map(relativize).toSet
      } else (Set.empty[Path], Set.empty[Path])
    val (oldDirs, oldFiles) = (subdirectories.keys.toSet, files.keys.toSet)
    val deletedDirs = oldDirs diff newDirs
    subdirectories --= deletedDirs
    val deletedFiles = oldFiles diff newFiles
    files --= deletedFiles
    val createdDirs = newDirs diff oldDirs
    subdirectories ++= createdDirs.view map (f => f -> Directory(resolve(f), callback))
    val createdFiles = newFiles diff oldFiles
    files ++= createdFiles.view.map(f =>
      f -> CachedFile(f, isDir = false, resolve(f).toFile.lastModified))
    deletedFiles foreach (f => callback(FileEvent(resolve(f), ENTRY_DELETE)))
    deletedDirs foreach (f => callback(FileEvent(resolve(f), ENTRY_DELETE)))
    createdDirs foreach (f => callback(FileEvent(resolve(f), ENTRY_CREATE)))
    createdFiles foreach (f => callback(FileEvent(resolve(f), ENTRY_CREATE)))
    this
  }

  private final val file = CachedFile(path, isDir = true, path.toFile.lastModified)
  private val subdirectories: mutable.Map[Path, Directory] = mutable.Map.empty
  private val files: mutable.Map[Path, CachedFile] = mutable.Map.empty
  private[this] val lock = new Object

  private def relativize(f: File) = path.relativize(f.toPath)

  private def resolve(p: Path) = path.resolve(p)

}
object Directory {
  private def apply(path: Path): Directory = ???
  def apply(path: Path, callback: Callback = _ => {}): Directory = {
    new Directory(path).traverse(callback)
  }
  case class FileEvent[T](path: Path, kind: WatchEvent.Kind[T])
  type Callback = FileEvent[_] => Unit
  private case class CachedFile(f: Path, isDir: Boolean, override val lastModified: Long)
      extends File(f.toString) {
    override lazy val isHidden: Boolean = f.toFile.isHidden

    override def isFile: Boolean = !isDir

    override def isDirectory: Boolean = isDir
  }
}
