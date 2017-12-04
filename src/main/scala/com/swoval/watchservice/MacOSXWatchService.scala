package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.nio.file._
import java.util.concurrent.{ArrayBlockingQueue, LinkedBlockingDeque, TimeUnit}
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
class MacOSXWatchService(watchLatency: Duration, val queueSize: Int)(onOffer: WatchKey => Unit)
  extends WatchService {

  override def close(): Unit = {
    if (open.compareAndSet(true, false)) {
      registered foreach { case (_, k) => cleanupKey(k) }
      CarbonAPI.INSTANCE.CFRunLoopStop(thread.runLoop)
      thread.interrupt()
      thread.join()
    }
  }

  override def init(): Unit = {}

  override def poll(timeout: Duration): WatchKey = {
    readyKeys.poll(timeout.toMicros, TimeUnit.MICROSECONDS)
  }

  override def pollEvents(): Map[WatchKey, Seq[WatchEvent[Path]]] = {
    registered.flatMap { case (_, k) =>
      val events = k.pollEvents()
      if (events.isEmpty) None
      else Some(k -> events.asScala.map(_.asInstanceOf[WatchEvent[Path]]))
    }.toMap[WatchKey, Seq[WatchEvent[Path]]]
  }

  override def register(path: Path, events: WatchEvent.Kind[Path]*): WatchKey = {
    registered get path match {
      case Some(k) => return k;
      case _ =>
    }
    val key = new MacOSXWatchKey(path, createStream(path), queueSize, events: _*)
    allFiles ++= mutable.Map(recursiveListFiles(path).view.filterNot(allFiles.contains).map { f =>
      f -> lastModified(f)
    }.toSeq: _*).groupBy { case (f, _) => f.getParent }

    registered collectFirst { case (p, k) if path startsWith p => k } match {
      case Some(_) =>
      case _ =>
        this.synchronized {
          key.schedule(thread.runLoop)
          thread.signal()
        }
    }
    registered foreach { case (p, k) => if ((p startsWith path) && (p != path)) cleanupKey(k) }
    registered += path -> key
    key
  }

  def onFileEvent(arg: (String, Int)): Unit = arg match {
    case (folderName, flags) =>
      import EventStreamFlags.{getFlags, MustScanSubDirs}
      val path = new File(folderName).toPath

      val folderFiles = if (getFlags(flags) contains MustScanSubDirs) recursiveListFiles(path) else {
        path.toFile match {
          case p if p.isDirectory =>
            p.listFiles.collect { case f if !f.isDirectory => f.toPath }.toSet
          case f => Set(f.toPath)
        }
      }

      val files = allFiles get path match {
        case Some(f) => f
        case None =>
          val map = mutable.Map.empty[Path, Long]
          allFiles = allFiles + (path -> map)
          map
      }

      val key = registered.getOrElse(path, return)

      @inline def signal(kind: WatchEvent.Kind[Path], file: Path, modified: Long): Unit = {
        files(file) = modified
        createEvent(key, kind, file)
      }

      folderFiles foreach { file =>
        val mtime = lastModified(file)
        files get file match {
          case Some(m) if m != mtime && key.reportModifyEvents => signal(ENTRY_MODIFY, file, mtime)
          case None if key.reportCreateEvents => signal(ENTRY_CREATE, file, mtime)
          case _ => // This file hasn't changed or the event isn't reported.
        }
      }

      files.keySet diff folderFiles foreach { file =>
        files -= file
        if (key.reportDeleteEvents) createEvent(key, ENTRY_DELETE, file)
      }
  }

  private def createEvent(key: MacOSXWatchKey, kind: WatchEvent.Kind[Path], file: Path): Unit = {
    key.addEvent(Event(kind, 1, file))
    if (!readyKeys.contains(key)) readyKeys.offer(key)
    onOffer(key)
  }

  private def cleanupKey(key: MacOSXWatchKey): Unit = {
    if (key.isValid) {
      key.unschedule(thread.runLoop)
    }
  }

  def isOpen: Boolean = open.get

  private def createStream(path: Path): FSEventStreamRef = {
    import EventStreamCreateFlags._
    val values = Array(CFStringRef.toCFString(path.toFile.getAbsolutePath).getPointer)
    val pathsToWatch = CFArrayCreate(null, values, 1, null)
    val sinceNow = -1L
    FSEventStreamCreate(Ptr.NULL, callback, Ptr.NULL, pathsToWatch, sinceNow, latency, NoDefer)
  }

  private[this] val latency = watchLatency.toMicros / 1e6
  private[this] val thread: CFRunLoopThread = new CFRunLoopThread
  private[this] val registered = mutable.Map.empty[Path, MacOSXWatchKey]

  private[this] val open = new AtomicBoolean(true)
  private[this] val readyKeys = new LinkedBlockingDeque[MacOSXWatchKey]

  private[this] lazy val callback: FSEventStreamCallback =
    (_: FSEventStreamRef, _: Ptr, numEvents: NativeLong, eventPaths: Ptr, flags: Ptr, _: Ptr) => {
      val count = numEvents.intValue()
      (eventPaths.getStringArray(0, count) zip flags.getIntArray(0, count)) foreach onFileEvent
    }

  private[this] var allFiles = Map.empty[Path, mutable.Map[Path, Long]]


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

private case class Event[T](kind: WatchEvent.Kind[T], count: Int, context: T) extends WatchEvent[T]

private class MacOSXWatchKey(
                              val watchable: Path,
                              makeStream: => FSEventStreamRef,
                              queueSize: Int,
                              kinds: WatchEvent.Kind[Path]*) extends WatchKey {

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

  override def reset(): Boolean = true

  override def toString = s"MacOSXWatchKey($watchable)"

  lazy val reportCreateEvents: Boolean = kinds contains ENTRY_CREATE
  lazy val reportModifyEvents: Boolean = kinds contains ENTRY_MODIFY
  lazy val reportDeleteEvents: Boolean = kinds contains ENTRY_DELETE

  private lazy val stream = makeStream
  private val events = new ArrayBlockingQueue[WatchEvent[_]](queueSize)
  private val scheduled = new AtomicBoolean(false)
  private val overflow = new AtomicInteger()
  private val valid = new AtomicBoolean(true)

  @inline def addEvent(event: Event[Path]): Unit = if (!events.offer(event)) {
    overflow.incrementAndGet()
    ()
  }

  @inline def schedule(loop: CFRunLoopRef): Unit = {
    if (isValid && scheduled.compareAndSet(false, true)) {
      FSEventStreamScheduleWithRunLoop(stream, loop, CFRunLoopThread.mode)
      FSEventStreamStart(stream)
      ()
    }
  }

  @inline def unschedule(loop: CFRunLoopRef): Unit = {
    cancel()
    if (scheduled.compareAndSet(true, false)) {
      FSEventStreamStop(stream)
      FSEventStreamUnscheduleFromRunLoop(stream, loop, CFRunLoopThread.mode)
      FSEventStreamInvalidate(stream)
      FSEventStreamRelease(stream)
      ()
    }
  }
}
