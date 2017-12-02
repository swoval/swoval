package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.nio.file._
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.stream.Collectors.toSet
import java.util.{Collections, List => JList}

import com.sun.jna.{NativeLong, Pointer => Ptr}
import com.swoval.watchservice.CarbonAPI.INSTANCE._
import sbt.io.WatchService

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try

/**
  * Provides the lion's share of the implementation details for receiving file system
  * events on OS X. Much of the logic was adapted from
  * https://github.com/takari/directory-watcher/
  * with primary contributions from Steve McLeod and Colin Decker
  *
  * @author Ethan Atkins
  */
class MacOSXWatchService(watchLatency: Duration, queueSize: Int) extends WatchService {

  override def close() = {
    if (open.compareAndSet(true, false)) {
      registered foreach { case (_, k) => cleanupKey(k) }
      if (thread != null) {
        CarbonAPI.INSTANCE.CFRunLoopStop(thread.runLoop)
        thread.interrupt()
        thread.join()
      }
    }
  }

  override def init(): Unit = {}

  override def poll(timeout: Duration): WatchKey =
    throw new UnsupportedOperationException("poll isn't used as of sbt.io 1.1.0")

  override def pollEvents(): Map[WatchKey, Seq[WatchEvent[Path]]] = {
    registered.flatMap { case (_, k) =>
      val events = k.pollEvents()
      if (events.isEmpty) None
      else Some(k -> events.asScala.map(_.asInstanceOf[WatchEvent[Path]]))
    }.toMap
  }

  override def register(path: Path, events: WatchEvent.Kind[Path]*): WatchKey = {
    val pathWatchKey = registered collectFirst { case (p, k) if path startsWith p => k } match {
      case Some(key) => key
      case _ =>
        registered get path match {
          case Some(key) =>
            key
          case None =>
            val key = new MacOSXWatchKey(path, queueSize, latency, events: _*)
            registered += path -> key
            this.synchronized {
              if (thread == null) {
                thread = new CFRunLoopThread(key.stream)
              } else {
                thread.addStream(key.stream)
              }
            }
            key
        }
    }
    registered foreach { case (p, key) =>
      if ((p startsWith path) && (p != path)) {
        pathWatchKey.addPath(p, key)
        cleanupKey(key)
      }
    }
    pathWatchKey
  }

  private def cleanupKey(key: MacOSXWatchKey) = {
    if (key.isValid) {
      key.cancel()
      FSEventStreamStop(key.stream)
      FSEventStreamUnscheduleFromRunLoop(key.stream, thread.runLoop, CFRunLoopThread.mode)
      FSEventStreamInvalidate(key.stream)
      FSEventStreamRelease(key.stream)
    }
  }

  def isOpen: Boolean = open.get

  private val open = new AtomicBoolean(true)
  private val latency = watchLatency.toMicros / 1e6
  private var thread: CFRunLoopThread = _
  private val registered = mutable.Map.empty[Path, MacOSXWatchKey]
}

private case class Event[T](kind: WatchEvent.Kind[T], count: Int, context: T) extends WatchEvent[T]

private class MacOSXWatchKey(
                              val watchable: Path,
                              queueSize: Int,
                              latency: Double,
                              kinds: WatchEvent.Kind[Path]*) extends WatchKey {

  override def cancel() = {
    valid.set(false)
  }

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

  override def reset(): Boolean = {
    true
  }

  override def toString = s"MacOSXWatchKey($watchable)"

  def addPath(path: Path, key: MacOSXWatchKey) = watchKeys += path -> key

  def onFileEvent(folderName: String): Unit = {
    val path = new File(folderName).toPath
    val folderFiles = recursiveListFiles(path)
    val files = allFiles get path match {
      case Some(f) => f
      case None =>
        val map = mutable.Map.empty[Path, Long]
        allFiles = allFiles + (path -> map)
        map
    }
    val key = watchKeys.getOrElse(path, this)

    @inline def signal(kind: WatchEvent.Kind[Path], file: Path, modified: Long) = {
      files(file) = modified
      key.onEvent(kind, file)
    }

    folderFiles foreach { file =>
      val modified = lastModified(file)
      files get file match {
        case Some(m) if m != modified && reportModifyEvents => signal(ENTRY_MODIFY, file, modified)
        case None if reportCreateEvents => signal(ENTRY_CREATE, file, modified)
        case _ => // This file hasn't changed or the event isn't reported.
      }
    }
    files.keySet diff folderFiles foreach { file =>
      files -= file
      if (reportDeleteEvents) onEvent(ENTRY_DELETE, file)
    }
  }

  val stream: FSEventStreamRef = {
    val values = Array(CFStringRef.toCFString(watchable.toFile.getAbsolutePath).getPointer)
    val pathsToWatch = CFArrayCreate(null, values, 1, null)
    val sinceNow = -1L
    val noDefer = 0x00000002
    FSEventStreamCreate(Ptr.NULL, callback, Ptr.NULL, pathsToWatch, sinceNow, latency, noDefer)
  }

  private var allFiles =
    mutable.Map(recursiveListFiles(watchable).map { f =>
      f -> lastModified(f)
    }.toSeq: _*).groupBy { case (f, _) => f.getParent }
  private val events = new ArrayBlockingQueue[WatchEvent[_]](queueSize)
  private val overflow = new AtomicInteger()
  private val valid = new AtomicBoolean(true)
  private val watchKeys = mutable.Map[Path, MacOSXWatchKey](watchable -> this)

  private lazy val callback: FSEventStreamCallback =
    (_: FSEventStreamRef, _: Ptr, numEvents: NativeLong, eventPaths: Ptr, _: Ptr, _: Ptr) =>
      eventPaths.getStringArray(0, numEvents.intValue()).foreach(onFileEvent)
  private lazy val reportCreateEvents = kinds contains ENTRY_CREATE
  private lazy val reportModifyEvents = kinds contains ENTRY_MODIFY
  private lazy val reportDeleteEvents = kinds contains ENTRY_DELETE

  @inline private def onEvent(kind: WatchEvent.Kind[Path], context: Path) = {
    if (!events.offer(Event(kind, 1, context))) {
      overflow.incrementAndGet()
    }
  }

  @inline private def lastModified(o: Path): Long = o.toFile match {
    case f if f.exists => f.lastModified
    case _ => 0L
  }

  @inline private def recursiveListFiles(path: Path): Set[Path] = path match {
    case p if p.toFile.isDirectory =>
      Try(Files.walk(path)).map {
        _.filter(p => Try(Files.isRegularFile(p)) getOrElse false)
          .collect(toSet[Path]).asScala.toSet
      } getOrElse Set.empty
    case p => Set(p)
  }
}
