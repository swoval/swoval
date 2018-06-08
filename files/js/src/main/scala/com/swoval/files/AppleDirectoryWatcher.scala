package com.swoval.files

import com.swoval.files.DirectoryWatcher.Event.Create
import com.swoval.files.DirectoryWatcher.Event.Delete
import com.swoval.files.DirectoryWatcher.Event.Modify
import com.swoval.files.apple.FileEvent
import com.swoval.files.apple.FileEventsApi
import com.swoval.files.apple.FileEventsApi.Consumer
import com.swoval.files.apple.Flags
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.HashMap
import java.util.Iterator
import java.util.Map
import java.util.Map.Entry
import java.util.concurrent.atomic.AtomicBoolean
import AppleDirectoryWatcher._

object AppleDirectoryWatcher {

  private val DefaultOnStreamRemoved: DefaultOnStreamRemoved =
    new DefaultOnStreamRemoved()

  private class Stream(path: Path, val id: Int, val maxDepth: Int) {

    private val compDepth: Int =
      if (maxDepth == java.lang.Integer.MAX_VALUE) maxDepth else maxDepth + 1

    def accept(base: Path, child: Path): Boolean = {
      val depth: Int = base.relativize(child).getNameCount
      depth <= compDepth
    }

  }

  /**
   * Callback to run when the native file events api removes a redundant stream. This can occur when
   * a child directory is registered with the watcher before the parent.
   */
  trait OnStreamRemoved {

    def apply(stream: String): Unit

  }

  class DefaultOnStreamRemoved() extends OnStreamRemoved {

    override def apply(stream: String): Unit = {}

  }

}

/**
 * Implements the DirectoryWatcher for Mac OSX using the [[https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html Apple File System Events Api]]
 */
class AppleDirectoryWatcher(private val latency: Double,
                            private val flags: Flags.Create,
                            private val executor: Executor,
                            onFileEvent: DirectoryWatcher.Callback,
                            onStreamRemoved: OnStreamRemoved)
    extends DirectoryWatcher {

  private val streams: Map[Path, Stream] = new HashMap()

  private val lock: AnyRef = new AnyRef()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val fileEventsApi: FileEventsApi = FileEventsApi.apply(
    new Consumer[FileEvent]() {
      override def accept(fileEvent: FileEvent): Unit = {
        executor.run(new Runnable() {
          override def run(): Unit = {
            val fileName: String = fileEvent.fileName
            val path: Path = Paths.get(fileName)
            val it: Iterator[Entry[Path, Stream]] =
              streams.entrySet().iterator()
            var validKey: Boolean = false
            while (it.hasNext && !validKey) {
              val entry: Entry[Path, Stream] = it.next()
              val key: Path = entry.getKey
              val stream: Stream = entry.getValue
              validKey = path == key || stream.accept(key, path)
            }
            if (validKey) {
              var event: DirectoryWatcher.Event = null
              event =
                if (fileEvent.itemIsFile())
                  if (fileEvent.isNewFile && Files.exists(path))
                    new DirectoryWatcher.Event(path, Create)
                  else if (fileEvent.isRemoved || !Files.exists(path))
                    new DirectoryWatcher.Event(path, Delete)
                  else new DirectoryWatcher.Event(path, Modify)
                else if (Files.exists(path))
                  new DirectoryWatcher.Event(path, Modify)
                else new DirectoryWatcher.Event(path, Delete)
              onFileEvent.apply(event)
            }
          }
        })
      }
    },
    new Consumer[String]() {
      override def accept(stream: String): Unit = {
        executor.run(new Runnable() {
          override def run(): Unit = {
            lock.synchronized {
              streams.remove(stream)
            }
            onStreamRemoved.apply(stream)
          }
        })
      }
    }
  )

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return true if the path is a directory and has not previously been registered
   */
  override def register(path: Path, maxDepth: Int): Boolean =
    register(path, flags, maxDepth)

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags [[com.swoval.files.apple.Flags.Create]] to set for the directory
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return true if the path is a directory and has not previously been registered
   */
  def register(path: Path, flags: Flags.Create, maxDepth: Int): Boolean = {
    var result: Boolean = true
    if (Files.isDirectory(path) && path != path.getRoot) {
      val entry: Entry[Path, Stream] = find(path)
      if (entry == null) {
        val id: Int =
          fileEventsApi.createStream(path.toString, latency, flags.getValue)
        if (id == -1) {
          result = false
          System.err.println("Error watching " + path + ".")
        } else {
          lock.synchronized {
            streams.put(path, new Stream(path, id, maxDepth))
          }
        }
      } else {
        val key: Path = entry.getKey
        val stream: Stream = entry.getValue
        val depth: Int =
          if (key == path) 0 else key.relativize(path).getNameCount
        var newMaxDepth: Int = 0
        if (maxDepth == java.lang.Integer.MAX_VALUE || stream.maxDepth == java.lang.Integer.MAX_VALUE) {
          newMaxDepth = java.lang.Integer.MAX_VALUE
        } else {
          val diff: Int = maxDepth - stream.maxDepth + depth
          newMaxDepth =
            if (diff > 0)
              (if (stream.maxDepth < java.lang.Integer.MAX_VALUE - diff)
                 stream.maxDepth + diff
               else java.lang.Integer.MAX_VALUE)
            else stream.maxDepth
        }
        if (newMaxDepth != stream.maxDepth) {
          streams.put(key, new Stream(path, stream.id, newMaxDepth))
        }
      }
    }
    result
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  override def unregister(path: Path): Unit = {
    lock.synchronized {
      if (!closed.get) {
        val stream: Stream = streams.remove(path)
        if (stream != null && stream.id != -1) {
          executor.run(new Runnable() {
            override def run(): Unit = {
              fileEventsApi.stopStream(stream.id)
            }
          })
        }
      }
    }
  }

  /**
 Closes the FileEventsApi and shuts down the {@code executor}.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      super.close()
      lock.synchronized {
        streams.clear()
      }
      fileEventsApi.close()
      executor.close()
    }
  }

  def this(latency: Double, flags: Flags.Create, onFileEvent: DirectoryWatcher.Callback) =
    this(latency,
         flags,
         Executor.make("com.swoval.files.AppleDirectoryWatcher.executorThread"),
         onFileEvent,
         DefaultOnStreamRemoved)

  def this(latency: Double,
           flags: Flags.Create,
           executor: Executor,
           onFileEvent: DirectoryWatcher.Callback) =
    this(latency, flags, executor, onFileEvent, DefaultOnStreamRemoved)

  private def find(path: Path): Entry[Path, Stream] = {
    val it: Iterator[Entry[Path, Stream]] = streams.entrySet().iterator()
    var result: Entry[Path, Stream] = null
    while (result == null && it.hasNext) {
      val entry: Entry[Path, Stream] = it.next()
      if (path.startsWith(entry.getKey)) {
        result = entry
      }
    }
    result
  }

}
