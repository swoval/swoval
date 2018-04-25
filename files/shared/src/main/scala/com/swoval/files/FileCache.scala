package com.swoval.files

import com.swoval.files.Directory.PathConverter
import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Delete, Modify }
import com.swoval.files.PathFilter.AllPass
import com.swoval.files.apple.Flags

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Properties

trait FileCache[P <: Path] extends AutoCloseable with Callbacks[P] {
  protected implicit def pathConverter: PathConverter[P]
  def list(path: Path, recursive: Boolean = true, filter: PathFilter = AllPass): Seq[Path]
  def register(path: Path, recursive: Boolean = true): Option[Directory[P]]
}

class FileCacheImpl[P <: Path](options: Options)(
    implicit override val pathConverter: PathConverter[P])
    extends FileCache[P] {
  private[this] val directories: mutable.Set[Directory[P]] = mutable.Set.empty
  private[this] val executor: ScheduledExecutor =
    platform.makeScheduledExecutor("com.swoval.files.FileCacheImpl.executor-thread")

  private[this] val scheduledFutures = mutable.Map.empty[Path, Future[FileWatchEvent[Path]]]

  def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[P] =
    directories
      .synchronized {
        if (path.exists) {
          directories.find(path startsWith _.path) match {
            case Some(dir) => dir.list(path, recursive, filter)
            case None =>
              register(path, recursive).fold(Seq.empty[P])(_.list(recursive, filter))
          }
        } else {
          Seq.empty
        }
      }

  override def register(path: Path, recursive: Boolean): Option[Directory[P]] = {
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
        case Seq(p) =>
          directories.find(p startsWith _.path) match {
            case Some(dir) => dir.update(path, !p.isDirectory, callback)
            case _         => // should be unreachable
          }
        case Seq() =>
          directories.synchronized {
            directories.filter(path startsWith _.path).toSeq.sortBy(_.path: Path).lastOption match {
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
              callback(FileWatchEvent[P](pathConverter.create(path), Delete))
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
  def apply[P <: Path: PathConverter](options: Options)(
      callback: Callbacks.Callback[P]): FileCacheImpl[P] = {
    val cache: FileCacheImpl[P] = new FileCacheImpl[P](options)
    cache.addCallback(callback)
    cache
  }
  def default[P <: Path: PathConverter]: FileCacheImpl[P] = new FileCacheImpl[P](Options.default)
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
