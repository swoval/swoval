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

  var DefaultOnStreamRemoved: DefaultOnStreamRemoved =
    new DefaultOnStreamRemoved()

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

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param recursive Toggles whether or not to monitor subdirectories
   * @return true if the path is a directory and has not previously been registered
   */
  override def register(path: Path, recursive: Boolean): Boolean =
    register(path, flags, recursive)

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags [[com.swoval.files.apple.Flags.Create]] to set for the directory
   * @param recursive Toggles whether the children of subdirectories should be monitored
   * @return true if the path is a directory and has not previously been registered
   */
  def register(path: Path, flags: Flags.Create, recursive: Boolean): Boolean = {
    if (Files.isDirectory(path) && path != path.getRoot) {
      if (!alreadyWatching(path)) {
        val id: Int =
          fileEventsApi.createStream(path.toString, latency, flags.getValue)
        if (id == -1) System.err.println("Error watching " + path + ".")
        else {
          lock.synchronized {
            streams.put(path.toString, id)
          }
        }
      }
      val rec: java.lang.Boolean = registered.get(path.toString)
      if (rec == null || !rec) registered.put(path.toString, recursive)
    }
    true
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  override def unregister(path: Path): Unit = {
    lock.synchronized {
      val id: java.lang.Integer = streams.remove(path.toString)
      if (id != null) {
        fileEventsApi.stopStream(id)
      }
      registered.remove(path.toString)
    }
  }

  /**
 Closes the FileEventsApi and shuts down the <code>executor</code>.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
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

  private def alreadyWatching(path: Path): Boolean =
    path != path.getRoot &&
      (streams.containsKey(path.toString) || alreadyWatching(path.getParent))

  private val registered: Map[String, Boolean] = new HashMap()

  private val streams: Map[String, Integer] = new HashMap()

  private val lock: AnyRef = new AnyRef()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val fileEventsApi: FileEventsApi = FileEventsApi.apply(
    new Consumer[FileEvent]() {
      override def accept(fileEvent: FileEvent): Unit = {
        executor.run(new Runnable() {
          override def run(): Unit = {
            val fileName: String = fileEvent.fileName
            val path: Path = Paths.get(fileName)
            val it: Iterator[Entry[String, Boolean]] =
              registered.entrySet().iterator()
            var validKey: Boolean = false
            while (it.hasNext && !validKey) {
              val entry: Entry[String, Boolean] = it.next()
              var key: String = entry.getKey
              validKey = fileName == key || (fileName
                .startsWith(key) && entry.getValue)
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
        onStreamRemoved.apply(stream)
        lock.synchronized {
          streams.remove(stream)
        }
      }
    }
  )

}
