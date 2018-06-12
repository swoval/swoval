package com.swoval.files.apple

import com.swoval.files.DirectoryWatcher.Event.Create
import com.swoval.files.DirectoryWatcher.Event.Delete
import com.swoval.files.DirectoryWatcher.Event.Modify
import com.swoval.files.DirectoryWatcher
import com.swoval.files.Executor
import com.swoval.files.apple.FileEventsApi.ClosedFileEventsApiException
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Map.Entry
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import AppleDirectoryWatcher._

object AppleDirectoryWatcher {

  private val DefaultOnStreamRemoved: DefaultOnStreamRemoved =
    new DefaultOnStreamRemoved()

  private class Stream(val path: Path, val id: Int, val maxDepth: Int) {

    private val compDepth: Int =
      if (maxDepth == java.lang.Integer.MAX_VALUE) maxDepth else maxDepth + 1

    def accept(child: Path): Boolean = {
      val depth: Int =
        if (child.startsWith(path)) path.relativize(child).getNameCount
        else java.lang.Integer.MAX_VALUE
      depth <= compDepth
    }

    override def toString(): String = "Stream(" + path + ", " + maxDepth + ")"

  }

  /**
 A no-op callback to invoke when streams are removed.
   */
  class DefaultOnStreamRemoved() extends Consumer[String] {

    override def accept(stream: String): Unit = {}

  }

}

/**
 * Implements the DirectoryWatcher for Mac OSX using the [[https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html Apple File System Events Api]]
 */
class AppleDirectoryWatcher(private val latency: Double,
                            private val flags: Flags.Create,
                            private val callbackExecutor: Executor,
                            onFileEvent: Consumer[DirectoryWatcher.Event],
                            onStreamRemoved: Consumer[String],
                            executor: Executor)
    extends DirectoryWatcher {

  private val streams: Map[Path, Stream] = new HashMap()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val internalExecutor: Executor =
    if (executor == null)
      Executor.make("com.swoval.files.apple.AppleDirectoryWatcher-internal-internalExecutor")
    else executor

  private val fileEventsApi: FileEventsApi = FileEventsApi.apply(
    new Consumer[FileEvent]() {
      override def accept(fileEvent: FileEvent): Unit = {
        callbackExecutor.run(new Runnable() {
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
              validKey = path == key || stream.accept(path)
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
              onFileEvent.accept(event)
            }
          }
        })
      }
    },
    new Consumer[String]() {
      override def accept(stream: String): Unit = {
        callbackExecutor.run(new Runnable() {
          override def run(): Unit = {
            new Runnable() {
              override def run(): Unit = {
                streams.remove(Paths.get(stream))
              }
            }.run()
            onStreamRemoved.accept(stream)
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
    val either: Either[Boolean, Exception] =
      internalExecutor.block(new Callable[Boolean]() {
        override def call(): Boolean =
          registerImpl(path, flags, maxDepth)
      })
    either.isLeft && either.left()
  }

  def registerImpl(path: Path, flags: Flags.Create, maxDepth: Int): Boolean = {
    var result: Boolean = true
    var realPath: Path = null
    try realPath = path.toRealPath()
    catch {
      case e: IOException => result = false

    }
    if (result && Files.isDirectory(realPath) && realPath != realPath.getRoot) {
      val entry: Entry[Path, Stream] = find(realPath)
      if (entry == null) {
        try {
          val id: Int = fileEventsApi.createStream(realPath.toString, latency, flags.getValue)
          if (id == -1) {
            result = false
            System.err.println("Error watching " + realPath + ".")
          } else {
            val newMaxDepth: Int = removeRedundantStreams(realPath, maxDepth)
            streams.put(realPath, new Stream(realPath, id, newMaxDepth))
          }
        } catch {
          case e: ClosedFileEventsApiException => {
            close()
            result = false
          }

        }
      } else {
        val key: Path = entry.getKey
        val stream: Stream = entry.getValue
        val depth: Int =
          if (key == realPath) 0 else key.relativize(realPath).getNameCount
        val newMaxDepth: Int = removeRedundantStreams(key, maxDepth)
        if (newMaxDepth != stream.maxDepth && stream.maxDepth >= depth) {
          streams.put(key, new Stream(key, stream.id, newMaxDepth))
        } else {
          streams.put(realPath, new Stream(realPath, -1, maxDepth))
        }
      }
    }
    result
  }

  private def removeRedundantStreams(path: Path, maxDepth: Int): Int = {
    val toRemove: List[Path] = new ArrayList[Path]()
    val it: Iterator[Entry[Path, Stream]] = streams.entrySet().iterator()
    var newMaxDepth: Int = maxDepth
    while (it.hasNext) {
      val e: Entry[Path, Stream] = it.next()
      val key: Path = e.getKey
      if (key.startsWith(path) && key != path) {
        val stream: Stream = e.getValue
        val depth: Int =
          if (key == path) 0 else key.relativize(path).getNameCount
        if (depth <= newMaxDepth) {
          toRemove.add(stream.path)
          if (stream.maxDepth > newMaxDepth - depth) {
            val diff: Int = stream.maxDepth - newMaxDepth + depth
            newMaxDepth =
              if (newMaxDepth < java.lang.Integer.MAX_VALUE - diff)
                newMaxDepth + diff
              else java.lang.Integer.MAX_VALUE
          }
        }
      }
    }
    val pathIterator: Iterator[Path] = toRemove.iterator()
    while (pathIterator.hasNext) unregisterImpl(pathIterator.next())
    newMaxDepth
  }

  private def unregisterImpl(path: Path): Unit = {
    if (!closed.get) {
      val stream: Stream = streams.remove(path)
      if (stream != null && stream.id != -1) {
        callbackExecutor.run(new Runnable() {
          override def run(): Unit = {
            fileEventsApi.stopStream(stream.id)
          }
        })
      }
    }
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  override def unregister(path: Path): Unit = {
    internalExecutor.block(new Runnable() {
      override def run(): Unit = {
        unregisterImpl(path)
      }
    })
  }

  /**
 Closes the FileEventsApi and shuts down the {@code callbackExecutor}.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      super.close()
      internalExecutor.block(new Runnable() {
        override def run(): Unit = {
          streams.clear()
          fileEventsApi.close()
          callbackExecutor.close()
        }
      })
      internalExecutor.close()
    }
  }

  /**
   * Creates a new AppleDirectoryWatcher which is a wrapper around [[FileEventsApi]], which in
   * turn is a native wrapper around [[https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1
   * Apple File System Events]]
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param onFileEvent [[Consumer]] to run on file events
   *     initialization
   */
  def this(latency: Double, flags: Flags.Create, onFileEvent: Consumer[DirectoryWatcher.Event]) =
    this(latency,
         flags,
         Executor.make("com.swoval.files.apple.AppleDirectoryWatcher.executorThread"),
         onFileEvent,
         DefaultOnStreamRemoved,
         null)

  /**
   * Creates a new AppleDirectoryWatcher which is a wrapper around [[FileEventsApi]], which in
   * turn is a native wrapper around [[https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1
   * Apple File System Events]]
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param callbackExecutor Executor to run callbacks on
   * @param onFileEvent [[Consumer]] to run on file events
   *     initialization
   */
  def this(latency: Double,
           flags: Flags.Create,
           callbackExecutor: Executor,
           onFileEvent: Consumer[DirectoryWatcher.Event]) =
    this(latency, flags, callbackExecutor, onFileEvent, DefaultOnStreamRemoved, null)

  /**
   * Creates a new AppleDirectoryWatcher which is a wrapper around [[FileEventsApi]], which in
   * turn is a native wrapper around [[https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1
   * Apple File System Events]]
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param onFileEvent [[Consumer]] to run on file events
   * @param internalExecutor The internal executor to manage the directory watcher state
   *     initialization
   */
  def this(latency: Double,
           flags: Flags.Create,
           onFileEvent: Consumer[DirectoryWatcher.Event],
           internalExecutor: Executor) =
    this(latency,
         flags,
         Executor.make("com.swoval.files.apple.AppleDirectoryWatcher.executorThread"),
         onFileEvent,
         DefaultOnStreamRemoved,
         internalExecutor)

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
