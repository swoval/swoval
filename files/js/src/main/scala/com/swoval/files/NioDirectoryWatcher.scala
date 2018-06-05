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
  override def register(path: Path, maxDepth: Int): Boolean = {
    def impl(p: File, depth: Int): Boolean = watchedDirs get p.toString match {
      case None if p.isDirectory =>
        val options = new FSWatcherOptions(recursive = false)
        val callback: js.Function2[nodejs.EventType, String, Unit] =
          (tpe: nodejs.EventType, name: String) => {
            val watchPath = p.toPath.resolve(Paths.get(name))
            val exists = Files.exists(watchPath)
            val kind: Event.Kind = tpe match {
              case "rename" if !exists => Delete
              case _                   => Modify
            }
            if (depth > 0 && Files.isDirectory(watchPath)) {
              impl(watchPath.toFile, depth - 1)
              FileOps.list(watchPath, recursive = true).asScala foreach { newPath =>
                if (newPath.isDirectory) {
                  impl(newPath, depth - 1)
                } else {
                  onFileEvent(new Event(watchPath, Create))
                }
              }
            }
            onFileEvent(new Event(watchPath, kind))
          }
        watchedDirs += p.toString -> WatchedDir(Fs.watch(p.toString, options, callback), depth)
        true
      case _ =>
        false
    }
    Files.exists(path) && impl(path.toFile, maxDepth) && {
      if (maxDepth > 0) FileOps.list(path, maxDepth, DirectoryFilter).asScala.foreach { p =>
        }
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

  private[this] case class WatchedDir(watcher: FSWatcher, maxDepth: Int) {
    private[this] val compDepth = if (maxDepth == Integer.MAX_VALUE) maxDepth else maxDepth + 1
    def accept(base: Path, child: Path): Boolean = {
      base.equals(child) || base.relativize(child).getNameCount <= compDepth
    }
  }
  private[this] val watchedDirs = mutable.Map.empty[String, WatchedDir]
}
