package com.swoval.watchservice

import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW }
import java.nio.file._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ArrayBlockingQueue, LinkedBlockingQueue, TimeUnit }
import java.util.{ Collections, List => JList }

import com.swoval.files.apple.Flags
import com.swoval.files.DirectoryWatcher.Event.{ Create, Delete, Modify }
import com.swoval.files.{ AppleDirectoryWatcher, DirectoryWatcher, Executor }
import sbt.io.WatchService

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.implicitConversions

/**
 * Provides the lion's share of the implementation details for receiving file system
 * events on OS X. Much of the logic was adapted from
 * https://github.com/takari/directory-watcher/
 * with primary contributions from Steve McLeod and Colin Decker
 *
 * @author Ethan Atkins
 */
class MacOSXWatchService(watchLatency: Duration, val queueSize: Int)(
    val onOffer: WatchKey => Unit = _ => {},
    val onEvent: WatchEvent[_] => Unit = _ => {}
) extends WatchService
    with AutoCloseable {

  private[this] val executor = Executor.make("com.swoval.files.MacOSXWatchService.executor-thread")
  private[this] val watcher = {
    val flags = new Flags.Create().setNoDefer().setFileEvents()
    new AppleDirectoryWatcher(watchLatency.toNanos / 1e9, flags, executor, onFileEvent(_))
  }

  override def close(): Unit = {
    if (open.compareAndSet(true, false)) {
      watcher.close()
    }
  }

  override def init(): Unit = {}

  override def poll(timeout: Duration): WatchKey = {
    readyKeys.poll(timeout.toNanos, TimeUnit.NANOSECONDS)
  }

  override def pollEvents(): Map[WatchKey, Seq[WatchEvent[Path]]] =
    registered
      .synchronized(registered.flatMap {
        case (_, k) =>
          val events = k.pollEvents()
          if (events.isEmpty) None
          else Some(k -> events.asScala.map(_.asInstanceOf[WatchEvent[Path]]))
      })
      .toMap[WatchKey, Seq[WatchEvent[Path]]]

  override def register(path: Path, events: WatchEvent.Kind[Path]*): WatchKey = {
    registered.synchronized {
      registered get path match {
        case Some(k) => k;
        case _ =>
          val key = new MacOSXWatchKey(path, queueSize, events: _*)
          registered += path -> key
          watcher.register(path.toRealPath())
          key
      }
    }
  }

  def onFileEvent(fileEvent: DirectoryWatcher.Event): Unit =
    executor.run(new Runnable {
      override def run() {
        registered
          .synchronized(registered get (fileEvent.path.getParent: Path)) foreach { key =>
          fileEvent.kind match {
            case Create if key.reportCreateEvents =>
              createEvent(key, ENTRY_CREATE, fileEvent.path)
            case Delete if key.reportDeleteEvents =>
              createEvent(key, ENTRY_DELETE, fileEvent.path)
            case Modify if key.reportModifyEvents =>
              createEvent(key, ENTRY_MODIFY, fileEvent.path)
            case _ =>
          }
        }
      }
    })

  private def createEvent(key: MacOSXWatchKey, kind: WatchEvent.Kind[Path], file: Path): Unit = {
    val event = Event(kind, 1, file)
    key.addEvent(event)
    onEvent(event)
    if (!readyKeys.contains(key)) {
      readyKeys.offer(key)
      onOffer(key)
    }
  }

  def isOpen: Boolean = open.get

  private[this] val open = new AtomicBoolean(true)
  private[this] val readyKeys = new LinkedBlockingQueue[MacOSXWatchKey]
  private[this] val registered = mutable.Map.empty[Path, MacOSXWatchKey]
}

private case class Event[T](kind: WatchEvent.Kind[T], count: Int, context: T) extends WatchEvent[T]

private class MacOSXWatchKey(val watchable: Path, queueSize: Int, kinds: WatchEvent.Kind[Path]*)
    extends WatchKey {

  override def cancel(): Unit = valid.set(false)

  override def isValid: Boolean = valid.get

  override def pollEvents(): JList[WatchEvent[_]] = {
    val result = new mutable.ArrayBuffer[WatchEvent[_]](events.size).asJava
    events.drainTo(result)
    val overflowCount = overflow.getAndSet(0)
    if (overflowCount != 0) {
      result.add(Event(OVERFLOW, overflowCount, watchable))
    }
    Collections.unmodifiableList(result)
  }

  override def reset(): Boolean = { events.clear(); true }

  override def toString = s"MacOSXWatchKey($watchable)"

  lazy val reportCreateEvents: Boolean = kinds contains ENTRY_CREATE
  lazy val reportModifyEvents: Boolean = kinds contains ENTRY_MODIFY
  lazy val reportDeleteEvents: Boolean = kinds contains ENTRY_DELETE

  private val events = new ArrayBlockingQueue[WatchEvent[_]](queueSize)
  private val overflow = new AtomicInteger()
  private val valid = new AtomicBoolean(true)

  @inline def addEvent(event: Event[Path]): Unit = if (!events.offer(event)) {
    overflow.incrementAndGet()
    ()
  }
}
