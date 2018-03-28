package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import io.scalajs.nodejs
import io.scalajs.nodejs.fs.{ FSWatcher, FSWatcherOptions, Fs }

import scala.collection.mutable
import scala.scalajs.js

class NioDirectoryWatcher(override val onFileEvent: Callback) extends DirectoryWatcher {
  override def register(path: Path, recursive: Boolean): Boolean = {
    def impl(p: Path): Boolean = watchedDirs get p.fullName match {
      case None if p.isDirectory =>
        val options = new FSWatcherOptions(recursive = false)
        val callback: js.Function2[nodejs.EventType, String, Unit] =
          (tpe: nodejs.EventType, name: String) => {
            val watchPath = p.resolve(Path(name))
            val exists = watchPath.exists
            val kind: FileWatchEvent.Kind = tpe match {
              case "rename" if !exists => Delete
              case _                   => Modify
            }
            onFileEvent(FileWatchEvent(watchPath, kind))
            if (recursive && tpe == "rename" && exists && watchPath.isDirectory) {
              watchPath.list(recursive, _ => true) foreach { newPath =>
                if (newPath.isDirectory) {
                  impl(newPath)
                } else {
                  onFileEvent(FileWatchEvent(newPath, Create))
                }
              }
            }
          }
        watchedDirs += p.fullName -> WatchedDir(Fs.watch(p.fullName, options, callback), recursive)
        true
      case _ =>
        false
    }
    path.exists && impl(path) && {
      if (recursive) path.list(recursive, _.isDirectory).foreach(impl)
      true
    }
  }
  override def unregister(path: Path): Unit = {
    watchedDirs.remove(path.fullName) foreach (_.watcher.close())
  }

  override def close(): Unit = {
    watchedDirs.values foreach (_.watcher.close())
    watchedDirs.clear()
  }

  private[this] case class WatchedDir(watcher: FSWatcher, recursive: Boolean)
  private[this] val watchedDirs = mutable.Map.empty[String, WatchedDir]
}
