package com.swoval.files

import java.io.{ File, FileFilter, IOException }
import java.nio.file.{ FileSystemLoopException, Files, Path, Paths }

import com.swoval.files.DirectoryWatcher.Event
import com.swoval.files.DirectoryWatcher.Event.{ Create, Delete, Modify }
import com.swoval.functional.{ Consumer, Either => SEither, Filter }
import io.scalajs.nodejs
import io.scalajs.nodejs.fs.{ FSWatcher, FSWatcherOptions, Fs }

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.scalajs.js

/**
 * Native directory watcher implementation for Linux and Windows
 *
 * @param onFileEvent The callback to run on file events
 */
class NioDirectoryWatcher(val onFileEvent: Consumer[Event]) extends DirectoryWatcher {
  def this(onFileEvent: Consumer[Event], executor: Executor) = this(onFileEvent)

  def this(onFileEvent: Consumer[Event], registerable: Registerable) = this(onFileEvent)

  private[files] def this(onFileEvent: Consumer[Event],
                          registerable: Registerable,
                          callbackExecutor: Executor,
                          executor: Executor) =
    this(onFileEvent)

  private object DirectoryFilter extends FileFilter {
    override def accept(pathname: File): Boolean = pathname.isDirectory()
  }

  private[this] val options = new FSWatcherOptions(recursive = false)

  /**
   * Register a path to monitor for file events
   *
   * @param path     The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return an [[com.swoval.functional.Either]] containing the result of the registration or an
   *         IOException if registration fails. This method should be idempotent and return true the
   *         first time the directory is registered or when the depth is changed. Otherwise it should
   *         return false.
   */
  override def register(path: Path, maxDepth: Int): SEither[IOException, Boolean] = {
    def impl(p: Path, depth: Int): Boolean = watchedDirs get p.toString match {
      case None                          => add(p, depth)
      case Some(d) if d.maxDepth < depth => add(p, depth)
      case _                             => false
    }

    def add(p: Path, depth: Int): Boolean = {
      val callback: js.Function2[nodejs.EventType, String, Unit] =
        (tpe: nodejs.EventType, name: String) => {
          val watchPath = p.resolve(Paths.get(name))
          val exists = Files.exists(watchPath)
          val kind: Event.Kind = tpe match {
            case "rename" if !exists => Delete
            case _                   => Modify
          }
          val events = new mutable.ArrayBuffer[Event]()
          if (depth > 0 && Files.isDirectory(watchPath)) {
            try {
              if (register(watchPath, if (depth == Integer.MAX_VALUE) depth else depth - 1)
                    .getOrElse(false)) {
                QuickList.list(watchPath, depth - 1).asScala foreach { newPath =>
                  events += new Event(newPath.toPath(), Create)
                }
              }
            } catch {
              case _: FileSystemLoopException =>
            }
          }
          onFileEvent.accept(new Event(watchPath, kind))
          events.foreach(onFileEvent.accept)
        }
      val watcher: FSWatcher = Fs.watch(p.toString, options, callback)
      watcher.onError(e => println(e))
      watchedDirs
        .put(p.toString, WatchedDir(watcher, depth))
        .foreach(_.watcher.close())
      true
    }

    try {
      SEither.right(Files.exists(path) && {
        val realPath = path.toRealPath()
        if (!path.equals(realPath) && watchedDirs.contains(realPath.toString))
          throw new FileSystemLoopException(path.toString)
        impl(path, maxDepth)
      } && (maxDepth == 0) || {
        QuickList
          .list(path, maxDepth = 0, followLinks = true, new Filter[QuickFile] {
            override def accept(file: QuickFile): Boolean = file.isDirectory()
          })
          .asScala
          .forall { dir =>
            register(dir.toPath(),
                     if (maxDepth == Integer.MAX_VALUE) Integer.MAX_VALUE else maxDepth - 1)
              .getOrElse(false)
          }
      })
    } catch {
      case e: IOException => SEither.left(e)
    }
  }

  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
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
