package com.swoval.files

import java.util.concurrent.atomic.AtomicBoolean

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import com.swoval.files.apple.{ FileSystemApi, Flags }

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.Duration

class AppleDirectoryWatcher(latency: Duration, flags: Flags.Create, executor: Executor)(
    override val onFileEvent: Callback,
    val onStreamRemoved: String => Unit = _ => {})
    extends DirectoryWatcher {
  override def close(): Unit = if (closed.compareAndSet(false, true)) {
    lock.synchronized { streams.clear() }
    fileSystemApi.close()
    executor.close()
  }

  def register(path: Path): Boolean = register(path, flags.value)
  def register(path: Path, flags: Int): Boolean = {
    if (!alreadyWatching(path)) {
      val stream = fileSystemApi.createStream(path.fullName, latency.toNanos / 1e9, flags)
      lock.synchronized(streams += path.fullName -> stream)
    }
    true
  }

  def unregister(path: Path): Unit = unregister(path.name)
  private def unregister(path: String): Unit = {
    lock.synchronized(streams get path match {
      case Some(streamHandle) if streamHandle != 0 =>
        streams -= path
        Some(streamHandle)
      case _ => None // Nothing registered, ignore event
    }) foreach fileSystemApi.stopStream
  }

  private[this] val streams = mutable.Map.empty[String, Int]
  private[this] val lock = new Object
  private[this] val closed = new AtomicBoolean(false)
  private[this] val fileSystemApi: FileSystemApi = try {
    FileSystemApi.apply(
      { fe =>
        executor.run(onFileEvent({
          val path = Path(fe.fileName)
          if (fe.itemIsFile) {
            fe match {
              case e if e.isNewFile => FileWatchEvent(path, Create)
              case e if e.isRemoved => FileWatchEvent(path, Delete)
              case _                => FileWatchEvent(path, Modify)
            }
          } else if (path.exists) {
            FileWatchEvent(path, Modify)
          } else {
            FileWatchEvent(path, Delete)
          }
        }))
      }, { s =>
        if (!closed.get) executor.run {
          lock.synchronized(streams -= s)
          onStreamRemoved(s)
        }
      }
    )
  } catch { case _: Throwable => sys.exit(1) }

  @tailrec @inline
  private[this] def alreadyWatching(path: Path): Boolean = {
    if (path == path.getRoot) false
    else (streams contains path.fullName) || alreadyWatching(path.getParent)
  }
}

object AppleDirectoryWatcher {
  def apply(latency: Duration, flags: Flags.Create)(
      onFileEvent: Callback,
      onStreamRemoved: String => Unit = _ => {}): AppleDirectoryWatcher = {
    val e: Executor = platform.makeExecutor("com.swoval.files.AppleDirectoryWatcher.executorThread")
    new AppleDirectoryWatcher(latency, flags, e)(onFileEvent, onStreamRemoved)
  }
}
