package com
package swoval
package files

import java.io.IOException
import java.nio.file.{ FileSystemLoopException, Files, Path, Paths }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import com.swoval.files.DirectoryWatcher.Event
import com.swoval.files.DirectoryWatcher.Event.{ Delete, Modify }
import com.swoval.functional.{ Consumer, IO }
import io.scalajs.nodejs
import io.scalajs.nodejs.fs.{ FSWatcherOptions, Fs }

import scala.scalajs.js
import scala.util.Try

/**
 * Native directory watcher implementation for Linux and Windows
 */
private[files] class NioDirectoryWatcherImpl(callback: Consumer[Event],
                                             callbackExecutor: Executor,
                                             internalExecutor: Executor,
                                             directoryRegistry: DirectoryRegistry,
                                             options: DirectoryWatcher.Option*)
    extends {
  private[this] val l: AtomicReference[Consumer[Event]] = new AtomicReference(null)
  private[this] val io = new NioDirectoryWatcherImpl.IOImpl(l)
} with NioDirectoryWatcher(io, callbackExecutor, internalExecutor, directoryRegistry, options: _*) {
  l.set(new Consumer[Event] {
    override def accept(e: Event) =
      if (directoryRegistry.accept(e.path)) handleEvent(callback, e.path, e.kind)
  })

  override def close(): Unit = {
    io.close()
    super.close()
  }
}
object NioDirectoryWatcherImpl {
  object EmptyConsumer extends Consumer[Event] {
    override def accept(e: Event): Unit = {}
  }
  class IOImpl(private[this] val l: AtomicReference[Consumer[Event]])
      extends IO[Path, WatchedDirectory]
      with AutoCloseable { self =>
    private[this] var closed = false
    private[this] val options = new FSWatcherOptions(recursive = false, persistent = false)
    private[this] var watchedDirectoriesByPath: Map[Path, WatchedDirectory] =
      Map.empty[Path, WatchedDirectory]

    override def close() {
      watchedDirectoriesByPath.toIndexedSeq.sortBy(_._1.toString).reverse.foreach(_._2.close())
      watchedDirectoriesByPath = Map.empty
    }
    override def apply(path: Path): functional.Either[IOException, WatchedDirectory] = {
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
          case Some(w) if w.isValid() => w
          case _ if Files.isDirectory(path) =>
            val cb: js.Function2[nodejs.EventType, String, Unit] =
              (tpe: nodejs.EventType, name: String) => {
                val watchPath = path.resolve(Paths.get(name))
                val exists = Files.exists(watchPath)
                val kind: Event.Kind = tpe match {
                  case "rename" if !exists => Delete
                  case _                   => Modify
                }
                l.get.accept(new Event(watchPath, kind))
              }

            val closed = new AtomicBoolean(false)
            val watcher = Fs.watch(path.toString, options, cb)
            watcher.onError { e =>
              closed.set(true)
              watcher.close()
              watchedDirectoriesByPath += path -> WatchedDirectories.INVALID
              l.get.accept(new Event(path, Event.Error))
            }
            val watchedDirectory: WatchedDirectory = new WatchedDirectory {

              /**
               * Is the underlying directory watcher valid?
               *
               * @return true if the underlying directory watcher is valid
               */
              override def isValid(): Boolean = true

              /**
               * Reset any queues for this directory
               */
              override def reset(): Unit = {}

              /**
               * Cancel the watch on this directory. Handle all non-fatal exceptions.
               */
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
}
