package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW }
import java.nio.file._
import java.util.concurrent._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.{ Collections, List => JList }

import com.sun.jna.{ NativeLong, Pointer => Ptr }
import com.swoval.watchservice.CarbonAPI.INSTANCE._
import sbt.io.WatchService

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.{ Set, mutable }
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
    onOffer: WatchKey => Unit = _ => {},
    onRegister: WatchKey => Unit = _ => {},
    onEvent: WatchEvent[_] => Unit = _ => {},
) extends WatchService {

  private[this] val executor = Executors.newSingleThreadExecutor()
  private[this] val cleanupExecutor = Executors.newSingleThreadExecutor()

  override def close(): Unit = {
    if (open.compareAndSet(true, false)) {
      executor.shutdown()
      cleanupExecutor.shutdown()
      registered foreach { case (_, k) => cleanupKey(k) }
      CarbonAPI.INSTANCE.CFRunLoopStop(thread.runLoop)
      thread.interrupt()
      thread.join()
    }
  }

  override def init(): Unit = {}

  override def poll(timeout: Duration): WatchKey = {
    readyKeys.poll(timeout.toNanos, TimeUnit.NANOSECONDS)
  }

  override def pollEvents(): Map[WatchKey, Seq[WatchEvent[Path]]] = {
    registered
      .synchronized(registered.flatMap {
        case (_, k) =>
          val events = k.pollEvents()
          if (events.isEmpty) None
          else Some(k -> events.asScala.map(_.asInstanceOf[WatchEvent[Path]]))
      })
      .toMap[WatchKey, Seq[WatchEvent[Path]]]
  }

  override def register(path: Path, events: WatchEvent.Kind[Path]*): WatchKey =
    registered.synchronized {
      registered get path match {
        case Some(k) => return k;
        case _       =>
      }
      val key = new MacOSXWatchKey(path, createStream(path), queueSize, events: _*)

      registered collectFirst { case (p, k) if path startsWith p => k } match {
        case Some(_) =>
          registered += path -> key
          onRegister(key)
        case _ =>
          // Coerce a deep copy with toIndexedSeq
          val excluded = registered.keySet.toIndexedSeq.toSet
          registered += path -> key
          key.schedule(thread.runLoop)
          thread.signal()
          submit(executor) {
            allFiles ++= recursiveListFiles(path, excluded).map(f => f -> lastModified(f))
            onRegister(key)
          }
      }
      submit(cleanupExecutor) {
        registered.synchronized(registered.toIndexedSeq) foreach {
          case (p, k) => if ((p startsWith path) && (p != path)) cleanupKey(k)
        }
      }
      key
    }

  private def onFileEvent(arg: (String, Int)): Unit =
    submit(executor)(arg match {
      case (folderName, flags) =>
        import EventStreamFlags.{ MustScanSubDirs, getFlags }
        val path = new File(folderName).toPath

        val folderFiles =
          if (getFlags(flags) contains MustScanSubDirs) recursiveListFiles(path, Set.empty)
          else {
            path.toFile match {
              case p if p.isDirectory =>
                p.listFiles.collect { case f if !f.isDirectory => f.toPath }.toSet
              case f => Set(f.toPath)
            }
          }

        registered.synchronized(registered get path) foreach {
          key =>
            @inline def signal(kind: WatchEvent.Kind[Path], file: Path, modified: Long): Unit = {
              allFiles(file) = modified
              createEvent(key, kind, file)
            }

            folderFiles foreach { file =>
              val mtime = lastModified(file)
              allFiles get file match {
                case Some(m) if m != mtime && key.reportModifyEvents =>
                  signal(ENTRY_MODIFY, file, mtime)
                case None if key.reportCreateEvents => signal(ENTRY_CREATE, file, mtime)
                case _                              => // This file hasn't changed or the event isn't reported.
              }
            }

            allFiles.keySet.filter(_.getParent == path) diff folderFiles foreach { file =>
              allFiles -= file
              if (key.reportDeleteEvents) createEvent(key, ENTRY_DELETE, file)
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

  private def cleanupKey(key: MacOSXWatchKey): Unit = {
    if (key.isValid) {
      key.unschedule(thread.runLoop)
    }
  }

  def isOpen: Boolean = open.get

  private def submit[R](service: ExecutorService)(f: => R): Unit = { service.submit(() => f); () }

  private def createStream(path: Path): FSEventStreamRef = {
    import EventStreamCreateFlags._
    val values = Array(CFStringRef.toCFString(path.toFile.getAbsolutePath).getPointer)
    val pathsToWatch = CFArrayCreate(null, values, 1, null)
    val sinceNow = -1L
    FSEventStreamCreate(Ptr.NULL, callback, Ptr.NULL, pathsToWatch, sinceNow, latency, NoDefer)
  }

  private[this] val allFiles = mutable.Map.empty[Path, Long]
  private[this] val latency = watchLatency.toMicros / 1e6
  private[this] val open = new AtomicBoolean(true)
  private[this] val readyKeys = new LinkedBlockingDeque[MacOSXWatchKey]
  private[this] val registered = mutable.Map.empty[Path, MacOSXWatchKey]
  private[this] val thread: CFRunLoopThread = new CFRunLoopThread

  private[this] lazy val callback: FSEventStreamCallback =
    (_: FSEventStreamRef, _: Ptr, numEvents: NativeLong, eventPaths: Ptr, flags: Ptr, _: Ptr) => {
      val count = numEvents.intValue()
      (eventPaths.getStringArray(0, count) zip flags.getIntArray(0, count)) foreach onFileEvent
    }

  @inline private def lastModified(o: Path): Long = o.toFile match {
    case f if f.exists => f.lastModified
    case _             => 0L
  }

  @inline private def recursiveListFiles(path: Path, exclude: Set[Path]): Set[Path] = {
    @tailrec def impl(paths: Seq[Path], exclude: Set[Path], accumulator: Set[Path]): Set[Path] = {
      val (directories, files) = paths
        .flatMap(_.toFile.listFiles.map(_.toPath))
        .filterNot(exclude)
        .partition(_.toFile.isDirectory)
      val newAccumulator = accumulator ++ files
      if (directories.nonEmpty) impl(directories, exclude, newAccumulator) else newAccumulator
    }

    val (directories, files) = path.toFile.listFiles.partition(_.isDirectory)
    impl(directories map (_.toPath) filterNot exclude, exclude, files.map(_.toPath).toSet)
  }
}

private case class Event[T](kind: WatchEvent.Kind[T], count: Int, context: T) extends WatchEvent[T]

private class MacOSXWatchKey(val watchable: Path,
                             makeStream: => FSEventStreamRef,
                             queueSize: Int,
                             kinds: WatchEvent.Kind[Path]*)
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
