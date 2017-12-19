package com.swoval.watchservice.files

import java.io.{ File, FileFilter }
import java.nio.file.StandardWatchEventKinds.{ ENTRY_DELETE, ENTRY_MODIFY }
import java.nio.file.{ Files, Path }
import java.util.concurrent.Executors

import com.swoval.watcher.{ AppleDirectoryWatcher, DirectoryWatcher, Flags }
import com.swoval.watchservice.files.Directory.{ Callback, FileEvent }
import com.swoval.watchservice.files.FileCache._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._

trait FileCache extends AutoCloseable {
  def list(path: Path, recursive: Boolean, filter: FileFilter): Seq[File]
}
trait DirectoryCache { self: FileCache =>
  def register(path: Path, onReg: Path => Unit): Directory
}
class FileCacheImpl(fileOptions: FileOptions, dirOptions: DirectoryOptions)(callback: Callback)
    extends FileCache
    with DirectoryCache {

  def list(path: Path, recursive: Boolean, filter: FileFilter): Seq[File] = lock.synchronized {
    directories.find(path isChildOf _.path) match {
      case Some(dir) => dir.list(path, recursive, filter)
      case None      => register(path, onReg = _ => {}).list(recursive, filter)
    }
  }

  override def register(path: Path, onReg: Path => Unit): Directory = {
    val dir = Directory(path, _ => {})
    watcher.register(path, onReg)
    lock.synchronized {
      directories foreach { dir =>
        if (dir.path isChildOf path) directories.remove(dir)
      }
      directories += dir
    }
    dir
  }

  override def close(): Unit = {
    watcher.close()
    executor.shutdownNow()
  }

  private[this] val executor = Executors.newSingleThreadExecutor
  private lazy val fileCallback: DirectoryWatcher.Callback = fileEvent =>
    executor.submit(() => {
      val path = new File(fileEvent.fileName).toPath
      if (path.toFile.exists) {
        list(path, recursive = false, _ => true) match {
          case Seq(p) if p == path.toFile =>
            callback(FileEvent(path, ENTRY_MODIFY))
          case Seq() =>
            directories.find(path isChildOf _.path) match {
              case Some(dir) if path != dir.path =>
                dir.add(path, isFile = path.toFile.isFile, callback)
              case _ =>
            }
          case Seq(p) =>
        }
      } else {
        directories.find(path isChildOf _.path) match {
          case Some(dir) =>
            if (dir.remove(path)) callback(FileEvent(path, ENTRY_DELETE))
          case _ =>
        }
      }
    })
  private lazy val directoryCallback: DirectoryWatcher.Callback = fileEvent =>
    executor.submit(() => {
      val path = new File(fileEvent.fileName).toPath
      directories.find(path isChildOf _.path) match {
        case Some(dir) if dir.path == path => dir.traverse(callback)
        case Some(dir) =>
          dir.find(path) match {
            case Some(Right(d)) => d.traverse(callback)
            case _              =>
          }
        case _ =>
      }
    })
  private[this] val watcher = new Watcher {
    val fileMonitor = fileOptions.toWatcher(fileCallback)
    val directoryMonitor = dirOptions.toWatcher(directoryCallback)

    override def close(): Unit = (directoryMonitor ++ fileMonitor) foreach (_.close())
    override def register(path: Path, onReg: Path => Unit) = {
      (directoryMonitor ++ fileMonitor) foreach (_.register(path, onReg))
    }
  }
  private[this] val directories: mutable.Set[Directory] = mutable.Set.empty
  private[this] object lock
  override def toString = s"FileCache($fileOptions, $dirOptions)"
}

class NoCache(fileOptions: FileOptions, dirOptions: DirectoryOptions) extends FileCache {
  private[this] val watcher = new Watcher {
    val fileMonitor = fileOptions.toWatcher(Callbacks.apply)
    val directoryMonitor = dirOptions.toWatcher(Callbacks.apply)

    override def close(): Unit = (directoryMonitor ++ fileMonitor) foreach (_.close())
    override def register(path: Path, onReg: Path => Unit) = {
      (directoryMonitor ++ fileMonitor) foreach (_.register(path, onReg))
    }
  }
  override def close(): Unit = watcher.close()
  override def list(path: Path, recursive: Boolean, filter: FileFilter): Seq[File] = {
    watcher.register(path, _ => {})
    if (path.toFile.exists()) {
      Files
        .walk(path)
        .iterator
        .asScala
        .collect { case f if filter.accept(f.toFile) => f.toFile }
        .toIndexedSeq
    } else {
      Seq.empty
    }
  }
}

object FileCache {
  object default extends FileCacheImpl(FileOptions.default, NoMonitor)(Callbacks)
  lazy val NoCache: FileCache = new NoCache(FileOptions.default, NoMonitor)
  def noCache(fileOptions: FileOptions): FileCache = new NoCache(fileOptions, NoMonitor)
  def noCache(fileOptions: FileOptions, dirOptions: DirectoryOptions): FileCache =
    new NoCache(fileOptions, dirOptions)
  implicit class RichPath(val p: Path) extends AnyVal {
    def isChildOf(other: Path): Boolean = p startsWith other
  }
  def apply(fileOptions: FileOptions) = new FileCacheImpl(fileOptions, NoMonitor)(Callbacks)
  def apply(dirOptions: DirectoryOptions) = new FileCacheImpl(NoMonitor, dirOptions)(Callbacks)
  def apply(fileOptions: FileOptions, dirOptions: DirectoryOptions)(callback: Callback) =
    new FileCacheImpl(fileOptions, dirOptions)(callback)
}

private trait Watcher extends AutoCloseable {
  def register(path: Path, onReg: Path => Unit): Unit
}

sealed trait Options {
  def toWatcher(callback: => DirectoryWatcher.Callback): Option[DirectoryWatcher]
}
sealed trait FileOptions extends Options
object FileOptions {
  def apply(latency: Duration, flags: Flags.Create): FileOptions = {
    new MonitorOptions(latency, flags) with FileOptions
  }
  lazy val default: FileOptions = FileOptions(10.milliseconds, Flags.Create.setFileEvents)
}
sealed trait DirectoryOptions extends Options
object DirectoryOptions {
  def apply(latency: Duration, flags: Flags.Create): DirectoryOptions = {
    new MonitorOptions(latency, flags) with DirectoryOptions
  }
  lazy val default: DirectoryOptions = DirectoryOptions(1.second, Flags.Create(0))
}
case object NoMonitor extends FileOptions with DirectoryOptions {
  override def toWatcher(callback: => DirectoryWatcher.Callback): Option[DirectoryWatcher] = None
}

abstract case class MonitorOptions private[files] (latency: Duration, flags: Flags.Create)
    extends Options {
  def toWatcher(callback: => DirectoryWatcher.Callback) =
    Some(new AppleDirectoryWatcher(latency, flags)(callback))

  {
    val fileEventsSet = (flags.value & Flags.Create.FileEvents) != 0
    this match {
      case _: FileOptions =>
        require(fileEventsSet, "FileEvent flag not set for file options")
      case _: DirectoryOptions =>
        require(!fileEventsSet, "FileEvent flag set for directory options")
      case _ => // should be unreachable in practice
        val msg = "MonitorOptions are created without mixing in File or Directory Options"
        throw new IllegalStateException(msg)
    }
  }
}
