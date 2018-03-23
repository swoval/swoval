package com.swoval.files

import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY }
import java.nio.file.{ Files => JFiles, Path => JPath, Paths => JPaths, _ }
import java.util.concurrent.Executors
import java.util.function.{ Consumer, Predicate }

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import com.swoval.files.JvmPath._
import com.swoval.files.NioDirectoryWatcher._
import com.swoval.files.compat._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

class NioDirectoryWatcher(override val onFileEvent: Callback) extends DirectoryWatcher {
  override def register(path: Path, recursive: Boolean = true): Boolean = {
    def impl(p: JPath): Boolean = lock.synchronized {
      val realPath = p.toRealPath()
      try {
        watchedDirs get realPath match {
          case None =>
            watchedDirs +=
              realPath -> WatchedDir(
                realPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY),
                recursive)
            true
          case _ => false
        }
      } catch {
        case _: NoSuchFileException => false
      }
    }
    val predicate: Predicate[JPath] = (p: JPath) => (p != path.path) && JFiles.isDirectory(p)
    val consumer: Consumer[JPath] = (p: JPath) => impl(p)
    path.exists && impl(path.path) && {
      walk(path.path, recursive)(_.filter(predicate).forEach(consumer))
      true
    }
  }
  override def unregister(path: Path): Unit = lock.synchronized {
    watchedDirs.remove(path.path) foreach {
      case WatchedDir(k, _) =>
        k.cancel(); k.reset()
    }
  }

  override def close(): Unit = lock.synchronized {
    lock.synchronized(watchedDirs.values) foreach {
      case WatchedDir(k, _) =>
        k.cancel(); k.reset()
    }
    executor.shutdownNow()
    watchService.close()
  }

  private def walk[R](path: JPath, recursive: Boolean)(f: java.util.stream.Stream[JPath] => R) = {
    val stream = if (recursive) JFiles.walk(path) else JFiles.list(path)
    try {
      f(stream)
    } catch {
      case e: NoSuchFileException => unregister(JvmPath(JPaths.get(e.getFile)))
    } finally {
      stream.close()
    }
  }

  private[this] case class WatchedDir(key: WatchKey, recursive: Boolean)
  private[this] val watchedDirs = mutable.Map.empty[JPath, WatchedDir]
  private[this] val watchService = FileSystems.getDefault.newWatchService
  private[this] val lock = new Object
  private[this] val executor = Executors.newSingleThreadExecutor

  executor.submit((() => {
    @tailrec
    def loop(): Unit = {
      val notifyNewFile: Consumer[JPath] =
        (p: JPath) => onFileEvent(FileWatchEvent(JvmPath(p), Create))
      val continue = try {
        val key = watchService.take()
        val keyPath = key.watchable().asInstanceOf[JPath]
        key.pollEvents().asScala foreach {
          e: WatchEvent[_] =>
            val path = key.watchable.asInstanceOf[JPath].resolve(e.context().asInstanceOf[JPath])
            val jvmPath = JvmPath(path)
            if (e.kind == ENTRY_CREATE && JFiles.exists(path) && JFiles.isDirectory(path)) {
              val recursive = watchedDirs.get(keyPath).exists(_.recursive)
              if (register(jvmPath, recursive))
                walk(path, recursive)(_.filter((_: JPath) != path).forEach(notifyNewFile))
            }
            onFileEvent(FileWatchEvent(jvmPath, e.kind.toSwoval))
        }
        if (!key.reset()) lock.synchronized(watchedDirs -= keyPath)
        true
      } catch {
        case _: InterruptedException        => false
        case _: ClosedWatchServiceException => false
        case _: NoSuchFileException         => false
        case e: Exception =>
          println(s"Unexpected exception $e ${e.getStackTrace mkString "\n"}")
          true
      }
      if (continue) loop()
    }
    loop()
  }): Runnable)
}
object NioDirectoryWatcher {
  implicit class RichWatchEvent(k: WatchEvent.Kind[_]) {
    def toSwoval: FileWatchEvent.Kind = k match {
      case ENTRY_CREATE => Create
      case ENTRY_DELETE => Delete
      case ENTRY_MODIFY => Modify
    }
  }
}
