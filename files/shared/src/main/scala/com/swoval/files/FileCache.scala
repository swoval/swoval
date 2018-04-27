package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Delete, Modify }
import com.swoval.files.PathFilter.AllPass
import com.swoval.files.apple.Flags

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Properties

trait FileCache extends AutoCloseable with Callbacks {
  def list(path: Path, recursive: Boolean = true, filter: PathFilter = AllPass): Seq[Path]
  def register(path: Path, recursive: Boolean = true): Option[Directory]
}

class FileCacheImpl(options: Options) extends FileCache {
  private[this] val directories: mutable.Set[Directory] = mutable.Set.empty[Directory]
  private[this] val executor: ScheduledExecutor =
    platform.makeScheduledExecutor("com.swoval.files.FileCacheImpl.executor-thread")
  private[this] val scheduledFutures = mutable.Map.empty[Path, Future[FileWatchEvent]]

  def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path] =
    directories
      .synchronized {
        if (path.exists) {
          directories.find(path startsWith _.path) match {
            case Some(dir) => dir.list(path, recursive, filter)
            case None =>
              register(path, recursive).fold(Seq.empty[Path])(_.list(recursive, filter))
          }
        } else {
          Seq.empty
        }
      }

  override def register(path: Path, recursive: Boolean): Option[Directory] = {
    if (path.exists) {
      watcher.foreach(_.register(path, recursive))
      directories.synchronized {
        if (!directories.exists(dir => path.startsWith(dir.path))) {
          directories foreach { dir =>
            if (dir.path startsWith path) directories.remove(dir)
          }
          val dir = Directory.of(path)
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

  private lazy val fileCallback: DirectoryWatcher.Callback = fileEvent => {
    scheduledFutures.get(fileEvent.path) match {
      case None =>
        val future = executor.schedule(options.latency)(fileEvent)
        future.foreach { fe =>
          scheduledFutures -= fileEvent.path
          scheduledCallback(fe)
        }(executor.toExecutionContext)
        scheduledFutures += fileEvent.path -> future
      case _ =>
    }
  }
  private lazy val scheduledCallback: DirectoryWatcher.Callback = fileEvent => {
    val path = fileEvent.path

    if (path.exists) {
      list(path, recursive = false, (_: Path) == path) match {
        case Seq(_) =>
          callback(FileWatchEvent(path, Modify))
        case Seq() =>
          directories.synchronized {
            directories.filter(path startsWith _.path).toSeq.sortBy(_.path).lastOption match {
              case Some(dir) if path != dir.path =>
                dir.add(path, isFile = !path.isDirectory, callback)
              case _ =>
            }
          }
        case _ =>
          assert(false) // Should be unreachable
      }
    } else {
      directories.synchronized {
        directories.find(path startsWith _.path) match {
          case Some(dir) =>
            if (dir.remove(path)) {
              callback(FileWatchEvent(path, Delete))
            }
          case _ =>
        }
      }
    }
  }
  private[this] val watcher = options.toWatcher(fileCallback, executor)
  override def toString = s"FileCache($options)"
}

object FileCache {
  def apply(options: Options)(callback: Callback): FileCacheImpl = {
    val cache: FileCacheImpl = new FileCacheImpl(options)
    cache.addCallback(callback)
    cache
  }
  def default: FileCacheImpl = new FileCacheImpl(Options.default)
}

sealed abstract class Options {
  def latency: FiniteDuration
  def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor): Option[DirectoryWatcher]
}
object Options {
  lazy val default: Options =
    FileOptions(10.milliseconds, new Flags.Create().setNoDefer.setFileEvents)
  case class FileOptions(latency: FiniteDuration, flags: Flags.Create) extends Options {
    override def toWatcher(callback: => DirectoryWatcher.Callback, e: Executor) = {
      Some(if (Properties.isMac) {
        new AppleDirectoryWatcher(latency / 3, flags, e)(callback)
      } else new NioDirectoryWatcher(callback))
    }
  }
  def apply(latency: FiniteDuration, flags: Flags.Create): Options = FileOptions(latency, flags)
}

case object NoMonitor extends Options {
  override def latency: FiniteDuration = 0.seconds
  override def toWatcher(callback: => DirectoryWatcher.Callback,
                         e: Executor): Option[DirectoryWatcher] = None
}
