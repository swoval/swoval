package com.swoval.files

import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY }
import java.nio.file.{ Files => JFiles, Path => JPath, Paths => JPaths, _ }
import java.util.concurrent.Executors

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import com.swoval.files.JvmPath._
import com.swoval.files.NioDirectoryWatcher._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

class NioDirectoryWatcher(override val onFileEvent: Callback) extends DirectoryWatcher {
  override def register(path: Path): Boolean = {
    def impl(p: JPath): Boolean = lock.synchronized {
      val realPath = p.toRealPath()
      try {
        watchedDirs get realPath match {
          case None =>
            watchedDirs +=
              realPath -> realPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            true
          case _ => false
        }
      } catch {
        case _: NoSuchFileException => false
      }
    }
    val res = path.exists && impl(path.path) && {
      walk(path.path)(_.filter(p => (p != path.path) && JFiles.isDirectory(p)).forEach(impl))
      true
    }
    res
  }
  override def unregister(path: Path): Unit = lock.synchronized {
    watchedDirs.remove(path.path) foreach { k =>
      k.cancel(); k.reset()
    }
  }

  override def close(): Unit = lock.synchronized {
    lock.synchronized(watchedDirs.values) foreach { k =>
      k.cancel(); k.reset()
    }
    executor.shutdownNow()
    watchService.close()
  }

  private def walk[R](path: JPath)(f: java.util.stream.Stream[JPath] => R) = {
    val stream = JFiles.walk(path)
    try {
      f(stream)
    } catch {
      case e: NoSuchFileException => unregister(JvmPath(JPaths.get(e.getFile)))
    } finally {
      stream.close()
    }
  }

  private[this] val watchedDirs = mutable.Map.empty[Watchable, WatchKey]
  private[this] val watchService = FileSystems.getDefault.newWatchService
  private[this] val lock = new Object
  private[this] val executor = Executors.newSingleThreadExecutor

  executor.submit((() => {
    @tailrec
    def loop(): Unit = {
      def notifyNewFile(p: JPath): Unit = onFileEvent(FileWatchEvent(JvmPath(p), Create))
      val continue = try {
        val key = watchService.take()
        key.pollEvents().asScala foreach {
          e: WatchEvent[_] =>
            val path = key.watchable.asInstanceOf[JPath].resolve(e.context().asInstanceOf[JPath])
            val jvmPath = JvmPath(path)
            if (e.kind == ENTRY_CREATE && JFiles.exists(path) && JFiles.isDirectory(path)) {
              if (register(jvmPath))
                walk(path)(_.filter(_ != path).forEach(notifyNewFile))
            }
            onFileEvent(FileWatchEvent(jvmPath, e.kind.toSwoval))
        }
        if (!key.reset()) lock.synchronized(watchedDirs -= key.watchable())
        true
      } catch {
        case _: InterruptedException        => false
        case _: ClosedWatchServiceException => false
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
