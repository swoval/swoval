package com
package swoval
package files

import java.io.IOException
import java.nio.file.{ FileSystemLoopException, Files, Path, Paths }
import java.util.concurrent.atomic.AtomicBoolean

import com.swoval.files.PathWatchers.{ Event, Overflow }
import com.swoval.files.PathWatchers.Event.Kind.{ Delete, Error, Modify }
import com.swoval.functional.Consumer
import io.scalajs.nodejs
import io.scalajs.nodejs.fs.{ FSWatcherOptions, Fs }

import scala.scalajs.js
import scala.util.Try

/**
 * Native directory watcher implementation for Linux and Windows
 */
private[files] class NioPathWatcherService(
    eventConsumer: Consumer[functional.Either[Overflow, Event]],
    registerable: RegisterableWatchService,
    internalExecutor: Executor)
    extends AutoCloseable {
  private[this] var closed = false
  private[this] val options = new FSWatcherOptions(recursive = false, persistent = false)
  private[this] var watchedDirectoriesByPath: Map[Path, WatchedDirectory] =
    Map.empty[Path, WatchedDirectory]

  override def close() {
    watchedDirectoriesByPath.toIndexedSeq.sortBy(_._1.toString).reverse.foreach(_._2.close())
    watchedDirectoriesByPath = Map.empty
  }
  def register(path: Path): functional.Either[IOException, WatchedDirectory] = {
    val realPath = Try(path.toRealPath()).getOrElse(path)
    if (path.startsWith(realPath) && !path.equals(realPath)) {
      functional.Either.left(new FileSystemLoopException(path.toString))
    } else {
      applyImpl(path)
    }
  }
  private def applyImpl(path: Path): functional.Either[IOException, WatchedDirectory] = {
    try {
      functional.Either.right(watchedDirectoriesByPath get path match {
        case Some(w) => w
        case _ if Files.isDirectory(path) =>
          val cb: js.Function2[nodejs.EventType, String, Unit] =
            (tpe: nodejs.EventType, name: String) => {
              val watchPath = path.resolve(Paths.get(name))
              val exists = Files.exists(watchPath)
              val kind: Event.Kind = tpe match {
                case "rename" if !exists => Delete
                case _                   => Modify
              }
              eventConsumer.accept(
                functional.Either.right(new Event(TypedPaths.get(watchPath), kind)))
            }

          val closed = new AtomicBoolean(false)
          val watcher = Fs.watch(path.toString, options, cb)
          watcher.onError { e =>
            closed.set(true)
            watcher.close()
            watchedDirectoriesByPath += path -> WatchedDirectories.INVALID
            eventConsumer.accept(functional.Either.right(new Event(TypedPaths.get(path), Error)))
          }
          val watchedDirectory: WatchedDirectory = new WatchedDirectory {
            override def close(): Unit = if (closed.compareAndSet(false, true)) {
              watchedDirectoriesByPath -= path
              watcher.close()
            }
          }
          watchedDirectoriesByPath += path -> watchedDirectory
          watchedDirectory
        case w =>
          w.foreach(_.close())
          watchedDirectoriesByPath += path -> WatchedDirectories.INVALID
          WatchedDirectories.INVALID
      })
    } catch {
      case e: IOException => functional.Either.left(e)
    }
  }
}
