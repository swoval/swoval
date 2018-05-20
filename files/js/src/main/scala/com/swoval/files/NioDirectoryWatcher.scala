package com.swoval.files

import java.io.{ File, FileFilter }

import java.nio.file.{ Files, Path, Paths }

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.DirectoryWatcher.Event
import com.swoval.files.DirectoryWatcher.Event.{ Create, Modify, Delete }
import io.scalajs.nodejs
import io.scalajs.nodejs.fs.{ FSWatcher, FSWatcherOptions, Fs }

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.scalajs.js

/**
 * Native directory watcher implementation for Linux and Windows
 * @param onFileEvent The callback to run on file events
 */
class NioDirectoryWatcher(val onFileEvent: Callback) extends DirectoryWatcher {
  def this(onFileEvent: Callback, registerable: Registerable) = this(onFileEvent)
  private object DirectoryFilter extends FileFilter {
    override def accept(pathname: File): Boolean = pathname.isDirectory
  }
  override def register(path: Path, recursive: Boolean): Boolean = {
    def impl(p: Path): Boolean = watchedDirs get p.toString match {
      case None if Files.isDirectory(p) =>
        val options = new FSWatcherOptions(recursive = false)
        val callback: js.Function2[nodejs.EventType, String, Unit] =
          (tpe: nodejs.EventType, name: String) => {
            val watchPath = p.resolve(Paths.get(name))
            val exists = Files.exists(watchPath)
            val kind: Event.Kind = tpe match {
              case "rename" if !exists => Delete
              case _                   => Modify
            }
            if (recursive && exists && Files.isDirectory(watchPath)) {
              impl(watchPath)
              FileOps.list(watchPath, recursive = true).asScala foreach { newPath =>
                if (Files.isDirectory(newPath)) {
                  impl(newPath)
                } else {
                  onFileEvent(new Event(watchPath, Create))
                }
              }
            }
            onFileEvent(new Event(watchPath, kind))
          }
        watchedDirs += p.toString -> WatchedDir(Fs.watch(p.toString, options, callback), recursive)
        true
      case _ =>
        false
    }
    Files.exists(path) && impl(path) && {
      if (recursive) FileOps.list(path, recursive, DirectoryFilter).asScala.foreach(impl(_: Path))
      true
    }
  }
  override def unregister(path: Path): Unit = {
    watchedDirs.remove(path.toString) foreach (_.watcher.close())
  }

  override def close(): Unit = {
    watchedDirs.values foreach (_.watcher.close())
    watchedDirs.clear()
  }

  private[this] case class WatchedDir(watcher: FSWatcher, recursive: Boolean)
  private[this] val watchedDirs = mutable.Map.empty[String, WatchedDir]
}
