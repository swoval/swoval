package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Delete, Modify }
import com.swoval.files.apple.Flags

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Properties

trait FileCache extends AutoCloseable {
  def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path]
}
trait DirectoryCache { self: FileCache =>
  def register(path: Path): Option[Directory]
}
class FileCacheImpl(fileOptions: FileOptions, dirOptions: DirectoryOptions, executor: Executor)(
    callback: Callback)
    extends FileCache
    with DirectoryCache {

  def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path] =
    lock
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
      val dir = Directory(path, _ => {})
      watcher.register(path)
      lock.synchronized {
        directories foreach { dir =>
          if (dir.path startsWith path) directories.remove(dir)
        }
        directories += dir
      }
      Some(dir)
    } else {
      None
    }
  }

  override def close(): Unit = {
    watcher.close()
    directories.clear()
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
            directories.find(path startsWith _.path) match {
              case Some(dir) if path != dir.path =>
                lock.synchronized(dir.add(path, isFile = !path.isDirectory, callback))
              case _ =>
            }
          case Seq(_) =>
        }
      } else {
        directories.find(path startsWith _.path) match {
          case Some(dir) =>
            if (dir.remove(path)) callback(FileWatchEvent(path, Delete))
          case _ =>
        }
      }
  }
  private lazy val directoryCallback: DirectoryWatcher.Callback = fileEvent =>
    executor.run {
      val path = fileEvent.path
      directories.find(path startsWith _.path) match {
        case Some(dir) if dir.path == path => dir.traverse(callback)
        case Some(dir) =>
          dir.find(path) match {
            case Some(Right(d)) => d.traverse(callback)
            case _              =>
          }
        case _ =>
      }
  }
  private[this] val watcher = new Watcher {
    val fileMonitor = fileOptions.toWatcher(fileCallback, executor)
    val directoryMonitor = dirOptions.toWatcher(directoryCallback, executor)

    override def close(): Unit = (directoryMonitor ++ fileMonitor) foreach (_.close())
    override def register(path: Path) = {
      (directoryMonitor ++ fileMonitor) foreach (_.register(path))
    }
  }
  private[this] val directories: mutable.Set[Directory] = mutable.Set.empty
  private[this] object lock
  override def toString = s"FileCache($fileOptions, $dirOptions)"
}

object FileCache {
  def apply(fileOptions: FileOptions, dirOptions: DirectoryOptions)(
      callback: Callback): FileCacheImpl = {
    val e: Executor = platform.makeExecutor("com.swoval.files.FileCacheImpl.executor-thread")
    new FileCacheImpl(fileOptions, dirOptions, e)(callback)
  }
}

private trait Watcher extends AutoCloseable {
  def register(path: Path): Unit
}

sealed trait Options {
  def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor): Option[DirectoryWatcher]
  def newWatcher(latency: Duration,
                 flags: Flags.Create,
                 callback: DirectoryWatcher.Callback,
                 e: Executor): Option[DirectoryWatcher] =
    if (Properties.isMac) {
      Some(new AppleDirectoryWatcher(latency, flags, e)(callback))
    } else Some(new NioDirectoryWatcher(callback))
}
sealed trait FileOptions extends Options
object FileOptions {
  def apply(latency: Duration, flags: Flags.Create): FileOptions = {
    new MonitorOptions(latency, flags) with FileOptions {
      def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor) =
        newWatcher(latency, flags, callback, e)
    }
  }
  lazy val default: FileOptions =
    FileOptions(10.milliseconds, new Flags.Create().setNoDefer().setFileEvents())
}
sealed trait DirectoryOptions extends Options
object DirectoryOptions {
  def apply(latency: Duration, flags: Flags.Create): DirectoryOptions = {
    new MonitorOptions(latency, flags) with DirectoryOptions {
      def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor) =
        newWatcher(latency, flags, callback, e)
    }
  }
  lazy val default: DirectoryOptions = DirectoryOptions(1.second, new Flags.Create().setNoDefer)
}
case object NoMonitor extends FileOptions with DirectoryOptions {
  override def toWatcher(callback: => DirectoryWatcher.Callback,
                         e: Executor): Option[DirectoryWatcher] = None
}

abstract case class MonitorOptions private[files] (latency: Duration, flags: Flags.Create)
    extends Options {

  {
    this match {
      case _: FileOptions =>
        require(flags.hasFileEvents, "FileEvent flag not set for file options")
      case _: DirectoryOptions =>
        require(!flags.hasFileEvents, "FileEvent flag set for directory options")
      case _ => // should be unreachable in practice
        val msg = "MonitorOptions are created without mixing in File or Directory Options"
        throw new IllegalStateException(msg)
    }
  }
}
