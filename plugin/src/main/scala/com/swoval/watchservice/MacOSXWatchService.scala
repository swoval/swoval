package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW }
import java.nio.file._
import java.util.concurrent._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.{ Collections, List => JList }

import com.swoval.watcher.{ AppleDirectoryWatcher, FileEvent, Flags }
import sbt.io.WatchService

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._

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
    val onRegister: WatchKey => Unit = _ => {},
    val onEvent: WatchEvent[_] => Unit = _ => {},
) extends WatchService
    with AutoCloseable {

  private[this] val executor = Executors.newSingleThreadExecutor()
  private[this] val watcher = {
    val flags = Flags.Create.setFileEvents
    new AppleDirectoryWatcher(watchLatency, flags)(onFileEvent)
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
          watcher.register(path, _ => onRegister(key))
          key
      }
    }
  }

  def onFileEvent(fileEvent: FileEvent): Unit = submit(executor) {
    if (fileEvent.itemIsFile) {
      val path = new File(fileEvent.fileName).toPath
      registered.synchronized(registered get path.getParent) foreach { key =>
        fileEvent match {
          case e if e.isNewFile && key.reportCreateEvents =>
            createEvent(key, ENTRY_CREATE, path)
          case e if e.isRemoved && key.reportDeleteEvents =>
            Some(ENTRY_DELETE)
            createEvent(key, ENTRY_DELETE, path)
          case _ if key.reportModifyEvents =>
            createEvent(key, ENTRY_MODIFY, path)
          case _ =>
        }
      }
    }
  }

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

  private def submit[R](service: ExecutorService)(f: => R): Unit = {
    service.submit(() => f); ()
  }

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
