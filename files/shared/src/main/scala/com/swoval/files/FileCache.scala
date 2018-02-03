package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Delete, Modify }
import com.swoval.files.apple.Flags

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Properties

trait FileCache extends AutoCloseable with Callbacks {
  def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path]
  def register(path: Path): Option[Directory]
}

class FileCacheImpl(options: Options, private[this] val directories: mutable.Set[Directory])
    extends FileCache {
  def this(options: Options) = this(options, mutable.Set.empty)
  private[this] val executor: Executor =
    platform.makeExecutor("com.swoval.files.FileCacheImpl.executor-thread")
  def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path] =
    directories
      .synchronized {
        if (path.exists) {
          directories.find(path startsWith _.path) match {
            case Some(dir) => dir.list(path, recursive, filter)
            case None      => register(path).map(_.list(recursive, filter)) getOrElse Seq.empty
          }
        } else {
          Seq.empty
        }
      }

  override def register(path: Path): Option[Directory] = {
    if (path.exists) {
      watcher.foreach(_.register(path))
      directories.synchronized {
        if (!directories.exists(dir => path.startsWith(dir.path))) {
          directories foreach { dir =>
            if (dir.path startsWith path) directories.remove(dir)
          }
          val dir = Directory(path, _ => {})
          directories += dir
          Some(dir)
        } else {
          None
        }
      }
    } else {
      None
    }
  }

  override def close(): Unit = closeImpl()
  protected def closeImpl(clearDirectoriesOnClose: Boolean = true): Unit = {
    watcher.foreach(_.close())
    if (clearDirectoriesOnClose) directories.clear()
    executor.close()
  }

  private lazy val fileCallback: DirectoryWatcher.Callback = fileEvent =>
    executor.run {
      val path = fileEvent.path
      if (path.exists) {
        list(path, recursive = false, _ => true) match {
          case Seq(p) if p == path =>
            callback(FileWatchEvent(path, Modify))
          case Seq() =>
            directories.synchronized {
              directories.find(path startsWith _.path) match {
                case Some(dir) if path != dir.path =>
                  dir.add(path, isFile = !path.isDirectory, callback)
                case _ =>
              }
            }
          case Seq(_) =>
        }
      } else {
        directories.synchronized {
          directories.find(path startsWith _.path) match {
            case Some(dir) =>
              if (dir.remove(path)) callback(FileWatchEvent(path, Delete))
            case _ =>
          }
        }
      }
  }
  private[this] val watcher = options.toWatcher(fileCallback, executor)
  for { w <- watcher; dir <- directories } w.register(dir.path)
  override def toString = s"FileCache($options)"
}

object FileCache {
  def apply(options: Options, directories: mutable.Set[Directory] = mutable.Set.empty)(
      callback: Callback): FileCacheImpl = {
    val cache: FileCacheImpl = new FileCacheImpl(options, directories)
    cache.addCallback(callback)
    cache
  }
  private[this] val directories: mutable.Set[Directory] = mutable.Set.empty
  def default: FileCacheImpl = new FileCacheImpl(Options.default, directories) {
    override def close(): Unit = closeImpl(clearDirectoriesOnClose = false)
  }
}

sealed abstract class Options {
  def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor): Option[DirectoryWatcher]
}
object Options {
  lazy val default: Options =
    FileOptions(10.milliseconds, new Flags.Create().setNoDefer.setFileEvents)
  case class FileOptions(latency: Duration, flags: Flags.Create) extends Options {
    override def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor) = {
      Some(if (Properties.isMac) {
        new AppleDirectoryWatcher(latency, flags, e)(callback)
      } else new NioDirectoryWatcher(callback))
    }
  }
  def apply(latency: Duration, flags: Flags.Create): Options = FileOptions(latency, flags)
}

case object NoMonitor extends Options {
  override def toWatcher(callback: => DirectoryWatcher.Callback,
                         e: Executor): Option[DirectoryWatcher] = None
}
