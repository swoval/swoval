// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.PathWatchers.Event.Kind.Create
import com.swoval.files.PathWatchers.Event.Kind.Delete
import com.swoval.files.PathWatchers.Event.Kind.Modify
import com.swoval.files.Executor.ThreadHandle
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatchers.Event
import com.swoval.files.apple.ClosedFileEventMonitorException
import com.swoval.files.apple.FileEvent
import com.swoval.files.apple.FileEventMonitor
import com.swoval.files.apple.FileEventMonitors
import com.swoval.files.apple.FileEventMonitors.Handle
import com.swoval.files.apple.FileEventMonitors.Handles
import com.swoval.files.apple.Flags
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Map.Entry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ApplePathWatcher._

object ApplePathWatcher {

  private val DefaultOnStreamRemoved: DefaultOnStreamRemoved =
    new DefaultOnStreamRemoved()

  private class Stream(val handle: Handle)

  /**
 A no-op callback to invoke when streams are removed.
   */
  class DefaultOnStreamRemoved() extends BiConsumer[String, ThreadHandle] {

    override def accept(stream: String, threadHandle: ThreadHandle): Unit = {}

  }

}

/**
 * Implements the PathWatcher for Mac OSX using the [[https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html Apple File System Events Api]].
 */
class ApplePathWatcher(private val latency: Long,
                       private val timeUnit: TimeUnit,
                       private val flags: Flags.Create,
                       onStreamRemoved: BiConsumer[String, ThreadHandle],
                       executor: Executor,
                       managedDirectoryRegistry: DirectoryRegistry)
    extends PathWatcher[PathWatchers.Event] {

  private val directoryRegistry: DirectoryRegistry =
    if (managedDirectoryRegistry == null) new DirectoryRegistryImpl()
    else managedDirectoryRegistry

  private val streams: Map[Path, Stream] = new HashMap()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val internalExecutor: Executor =
    if (executor == null)
      Executor.make("com.swoval.files.ApplePathWatcher-internalExecutor")
    else executor

  private val fileEventMonitor: FileEventMonitor = FileEventMonitors.get(
    new Consumer[FileEvent]() {
      override def accept(fileEvent: FileEvent): Unit = {
        if (!closed.get) {
          internalExecutor.run(new Consumer[ThreadHandle]() {
            override def accept(threadHandle: ThreadHandle): Unit = {
              val fileName: String = fileEvent.fileName
              val path: TypedPath = TypedPaths.get(Paths.get(fileName))
              if (directoryRegistry.accept(path.getPath)) {
                var event: Event = null
                event =
                  if (fileEvent.itemIsFile())
                    if (fileEvent.isNewFile && path.exists())
                      new Event(path, Create)
                    else if (fileEvent.isRemoved || !path.exists())
                      new Event(path, Delete)
                    else new Event(path, Modify)
                  else if (path.exists()) new Event(path, Modify)
                  else new Event(path, Delete)
                try observers.onNext(event)
                catch {
                  case e: RuntimeException => observers.onError(e)

                }
              }
            }
          })
        }
      }
    },
    new Consumer[String]() {
      override def accept(stream: String): Unit = {
        if (!closed.get) {
          try {
            val threadHandle: ThreadHandle = internalExecutor.getThreadHandle
            try {
              streams.remove(Paths.get(stream))
              onStreamRemoved.accept(stream, threadHandle)
            } finally threadHandle.release()
          } catch {
            case e: InterruptedException => {}

          }
        }
      }
    }
  )

  private val observers: Observers[PathWatchers.Event] = new Observers()

  override def addObserver(observer: Observer[Event]): Int =
    observers.addObserver(observer)

  override def removeObserver(handle: Int): Unit = {
    observers.removeObserver(handle)
  }

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return an [[com.swoval.functional.Either]] containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  override def register(path: Path, maxDepth: Int): Either[IOException, Boolean] =
    register(path, flags, maxDepth)

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags [[com.swoval.files.apple.Flags.Create]] to set for the directory
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return an [[com.swoval.functional.Either]] containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  def register(path: Path, flags: Flags.Create, maxDepth: Int): Either[IOException, Boolean] =
    try {
      val threadHandle: ThreadHandle = internalExecutor.getThreadHandle
      try Either.right(registerImpl(path, flags, maxDepth, threadHandle))
      finally threadHandle.release()
    } catch {
      case e: InterruptedException => Either.right(false)

    }

  private def registerImpl(path: Path,
                           flags: Flags.Create,
                           maxDepth: Int,
                           threadHandle: ThreadHandle): Boolean = {
    var result: Boolean = true
    val entry: Entry[Path, Stream] = find(path)
    directoryRegistry.addDirectory(path, maxDepth)
    if (entry == null) {
      try {
        val id: FileEventMonitors.Handle =
          fileEventMonitor.createStream(path, latency, timeUnit, flags)
        if (id == Handles.INVALID) {
          result = false
        } else {
          removeRedundantStreams(path, threadHandle)
          streams.put(path, new Stream(id))
        }
      } catch {
        case e: ClosedFileEventMonitorException => {
          close()
          result = false
        }

      }
    }
    result
  }

  private def removeRedundantStreams(path: Path, threadHandle: ThreadHandle): Unit = {
    val toRemove: List[Path] = new ArrayList[Path]()
    val it: Iterator[Entry[Path, Stream]] = streams.entrySet().iterator()
    while (it.hasNext) {
      val e: Entry[Path, Stream] = it.next()
      val key: Path = e.getKey
      if (key.startsWith(path) && key != path) {
        toRemove.add(key)
      }
    }
    val pathIterator: Iterator[Path] = toRemove.iterator()
    while (pathIterator.hasNext) unregisterImpl(pathIterator.next(), threadHandle)
  }

  private def unregisterImpl(path: Path, threadHandle: ThreadHandle): Unit = {
    if (!closed.get) {
      directoryRegistry.removeDirectory(path)
      val stream: Stream = streams.remove(path)
      if (stream != null && stream.handle != Handles.INVALID) {
        try fileEventMonitor.stopStream(stream.handle)
        catch {
          case e: ClosedFileEventMonitorException =>
            e.printStackTrace(System.err)

        }
      }
    }
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  override def unregister(path: Path): Unit = {
    try {
      val threadHandle: ThreadHandle = internalExecutor.getThreadHandle
      try unregisterImpl(path, threadHandle)
      finally threadHandle.release()
    } catch {
      case e: InterruptedException => {}

    }
  }

  /**
 Closes the FileEventsApi and shuts down the {@code internalExecutor}.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      try {
        val threadHandle: ThreadHandle = internalExecutor.getThreadHandle
        try {
          val it: Iterator[Stream] = streams.values.iterator()
          var stop: Boolean = false
          while (it.hasNext && !stop) try fileEventMonitor.stopStream(it.next().handle)
          catch {
            case e: ClosedFileEventMonitorException => stop = true

          }
          streams.clear()
          fileEventMonitor.close()
        } finally threadHandle.release()
      } catch {
        case e: InterruptedException => {}

      }
      internalExecutor.close()
    }
  }

  def this(executor: Executor, directoryRegistry: DirectoryRegistry) =
    this(10,
         TimeUnit.MILLISECONDS,
         new Flags.Create().setNoDefer().setFileEvents(),
         DefaultOnStreamRemoved,
         executor,
         directoryRegistry)

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

object ApplePathWatchers {

  def get(executor: Executor,
          directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] =
    new ApplePathWatcher(executor, directoryRegistry)

}
