package com.swoval.files

import com.swoval.files.Directory.PathConverter
import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete }
import com.swoval.files.PathFilter.AllPass

import scala.annotation.tailrec
import scala.collection.mutable

case class Directory[P <: Path] private (path: P) {
  def close(): Unit = {
    subdirectories.values foreach (_.close())
    subdirectories.clear()
    files.clear()
  }
  def add(abspath: Path, isFile: Boolean, callback: Callback)(
      implicit c: PathConverter[P]): Boolean = {
    @tailrec def implRec(directory: Directory[P], parts: Seq[Path]): Boolean = {
      parts match {
        case Seq(p) =>
          val newPath = lock.synchronized {
            if (isFile) {
              directory.files.put(p.name, c.relativize(directory.path, c.create(abspath))).isEmpty
            } else {
              directory.subdirectories get p.name match {
                case None =>
                  val dir = Directory.of[P](directory.resolve(p), callback)
                  directory.subdirectories += p.name -> dir
                  true
                case _ =>
                  false
              }
            }
          }
          if (newPath) callback(FileWatchEvent(directory.resolve(p), Create))
          newPath
        case Seq(p, rest @ _*) =>
          lock.synchronized(directory.subdirectories get p.name) match {
            case None =>
              lock.synchronized {
                val dir = Directory.of(directory.resolve(p), Directory.EmptyCallback)
                directory.subdirectories += p.name -> dir
                @inline def notifyNewFile(p: Path): Unit = callback(FileWatchEvent(p, Create))
                dir.list(recursive = true, AllPass).foreach(notifyNewFile)
                val child = dir.path.resolve(Path(rest.map(_.name): _*))
                dir.find(child).isDefined
              }
            case Some(d) => implRec(d, rest)
          }
      }
    }
    abspath match {
      case p if !p.isAbsolute                       => implRec(this, p.parts)
      case p if p.isAbsolute && (p startsWith path) => implRec(this, path.relativize(p).parts)
      case _                                        => false
    }
  }

  def find(abspath: Path)(implicit c: PathConverter[P]): Option[Either[P, Directory[P]]] = {
    @tailrec def impl(dir: Directory[P], parts: Seq[Path]): Option[Either[P, Directory[P]]] = {
      parts match {
        case Seq(p) =>
          lock.synchronized {
            val subdir = dir.subdirectories get p.name map Right.apply
            subdir orElse (dir.files get p.name map (f => Left(c.resolve(dir.path, f))))
          }
        case Seq(p, rest @ _*) =>
          lock.synchronized(dir.subdirectories get p.name) match {
            case Some(d) => impl(d, rest)
            case _       => None
          }
        case Seq() => None
      }
    }
    abspath match {
      case p if p.name == path.name => Some(Right(this))
      case p if !p.isAbsolute       => impl(this, p.parts)
      case p if p.isAbsolute && (p startsWith path) =>
        impl(this, path.relativize(p).parts)
      case _ => None
    }
  }

  def list(recursive: Boolean, filter: PathFilter)(implicit c: PathConverter[P]): Seq[P] =
    lock.synchronized {
      def resolve(p: P): P = c.resolve(path, p)
      val cachedFiles = files.values
      val subdirs = subdirectories.values

      (cachedFiles.view.map(resolve).filter(filter).toSeq ++
        subdirs.view.map(_.path).map(resolve).filter(filter)).toIndexedSeq ++
        (if (recursive) subdirs.flatMap(_.list(recursive, filter)) else Seq.empty).toIndexedSeq
    }

  def list(path: Path, recursive: Boolean, filter: PathFilter)(
      implicit c: PathConverter[P]): Seq[P] =
    find(path) match {
      case Some(Right(d)) => d.list(recursive, filter)
      case Some(Left(f))  => Seq(c.resolve(Directory.this.path, f))
      case None           => Seq.empty
    }

  def remove(abspath: Path): Boolean = {
    @tailrec def impl(dir: Directory[P], parts: Seq[Path]): Boolean = {
      parts match {
        case Seq(p: Path) =>
          lock.synchronized(
            dir.files.remove(p.name).isDefined || dir.subdirectories.remove(p.name).isDefined)
        case Seq(p: Path, rest @ _*) =>
          lock.synchronized(dir.subdirectories get p.name) match {
            case Some(d) => impl(d, rest)
            case _       => false
          }
      }
    }
    abspath match {
      case p if !p.isAbsolute => impl(this, Seq(abspath))
      case p if p.isAbsolute && (p startsWith path) =>
        impl(this, path.relativize(p).parts)
      case _ => false
    }
  }

  def traverse(callback: Callback)(implicit c: PathConverter[P]): Directory[P] = lock.synchronized {
    def keys(paths: Seq[Path]) = {
      paths.map(p => path.relativize(p).name).toSet
    }

    val (newDirs, newFiles) =
      if (path.exists) path.list(recursive = false).partition(_.isDirectory) match {
        case (a, b) => keys(a) -> keys(b)
      } else (Set.empty[String], Set.empty[String])
    val (oldDirs, oldFiles) = (subdirectories.keys.toSet, files.keys.toSet)
    val deletedDirs = oldDirs diff newDirs
    subdirectories --= deletedDirs
    val deletedFiles = oldFiles diff newFiles
    files --= deletedFiles
    val createdDirs = newDirs diff oldDirs
    subdirectories ++= createdDirs.view map (f =>
      f -> Directory.of(path.resolve(Path(f)), callback))
    val createdFiles = newFiles diff oldFiles
    files ++= createdFiles.map(f => f -> c.relativize(path, c.create(path.resolve(Path(f)))))
    callback match {
      case Directory.EmptyCallback =>
      case _ =>
        deletedFiles foreach (f => callback(FileWatchEvent(path.resolve(Path(f)), Delete)))
        deletedDirs foreach (f => callback(FileWatchEvent(path.resolve(Path(f)), Delete)))
        createdDirs foreach (f => callback(FileWatchEvent(path.resolve(Path(f)), Create)))
        createdFiles foreach (f => callback(FileWatchEvent(path.resolve(Path(f)), Create)))
    }
    this
  }
  private def resolve(other: Path): Path = path.resolve(other)

  private val subdirectories: mutable.Map[String, Directory[P]] = mutable.Map.empty
  private val files: mutable.Map[String, P] = mutable.Map.empty
  private[this] val lock = new Object
}

object Directory {
  trait PathConverter[P <: Path] {
    def create(p: Path): P
    def relativize(left: Path, right: P): P
    def resolve(left: Path, right: P): P
  }
  object PathConverter {
    implicit object default extends PathConverter[Path] {
      @inline def create(p: Path): Path = p
      @inline def relativize(left: Path, right: Path): Path = left.relativize(right)
      @inline def resolve(left: Path, right: Path): Path = {
        left.resolve(right)
      }
    }
  }
  case object EmptyCallback extends Callback {
    override def apply(e: FileWatchEvent): Unit = {}
  }
  def of[P <: Path](path: Path, callback: Callback = EmptyCallback)(
      implicit p: PathConverter[P]): Directory[P] =
    new Directory[P](p.create(path)).traverse(callback)
}
