package com.swoval.watcher

import java.io.File
import java.nio.file.{ NoSuchFileException, Path }
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ CountDownLatch, ExecutorService, Executors }

import com.swoval.watcher.DirectoryWatcher.Callback
import com.swoval.watcher.AppleFileSystemApi._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._

object DirectoryWatcher {
  type Callback = FileEvent => Unit
}
abstract class DirectoryWatcher extends AutoCloseable {
  def onFileEvent: Callback
  def register(path: Path, onRegister: Path => Unit = _ => {}): Boolean
  def unregister(path: Path): Unit
}

class AppleDirectoryWatcher(latency: Duration, flags: Flags.Create)(
    override val onFileEvent: Callback)
    extends DirectoryWatcher {
  override def close(): Unit = if (closed.compareAndSet(false, true)) {
    streams foreach { case (_, h) if h != 0 => AppleFileSystemApi.stopStream(handle, h) }
    streams.clear()
    AppleFileSystemApi.close(handle)
    lock.synchronized(lock.notifyAll())
    thread.interrupt()
    thread.join()
    Seq(executor, cleanupExecutor) foreach (_.shutdown())
  }

  def register(path: Path, onRegister: Path => Unit) = register(path, flags.value, onRegister)
  def register(path: Path, flags: Int, onRegister: Path => Unit): Boolean =
    try {
      val realPath = path.toRealPath()
      submit(executor) {
        if (!alreadyWatching(realPath)) {
          val stream = createStream(realPath.toString, latency.toNanos / 1e9, flags, handle)
          if (stream != 0) streams += path -> stream
          thread.signalLatch.countDown()
          lock.synchronized {
            needCleanup.set(true)
            lock.notifyAll()
          }
        }
        onRegister(realPath)
      }
      true
    } catch { case e: NoSuchFileException => false }

  def unregister(path: Path): Unit = submit(executor) {
    streams get path match {
      case Some(streamHandle) if streamHandle != 0 =>
        AppleFileSystemApi.stopStream(handle, streamHandle)
        streams -= path
      case None => // Nothing registered, ignore event
    }
  }

  private[this] val executor = Executors.newSingleThreadExecutor()
  private[this] val cleanupExecutor = Executors.newSingleThreadExecutor()
  //private[this] val streams = mutable.Map.empty[Path, Long]
  val streams = mutable.Map.empty[Path, Long]
  private[this] val thread = new DirectoryWatcherThread
  private[this] val lock = new Object
  private[this] val closed = new AtomicBoolean(false)
  private[this] var needCleanup = new AtomicBoolean(false)
  private[this] lazy val handle = try {
    init(onFileEvent)
  } catch { case _: Throwable => sys.exit(1) }

  submit(cleanupExecutor)(cleanupLoop())
  @tailrec
  private def cleanupLoop(): Unit = {
    def ready() = !closed.get && needCleanup.compareAndSet(true, false)
    if (lock.synchronized { ready() || { lock.wait(); ready() } }) {
      streams.keys filter (p => alreadyWatching(p.getParent)) foreach unregister
    }
    if (!closed.get) cleanupLoop()
  }

  private[this] class DirectoryWatcherThread extends Thread("DirectoryWatcher") {
    val initLatch = new CountDownLatch(1)
    val signalLatch = new CountDownLatch(1)
    setDaemon(true)
    start()
    try initLatch.await()
    catch { case _: InterruptedException => }

    override def run(): Unit =
      try {
        handle
        initLatch.countDown()
        try {
          signalLatch.await()
          AppleFileSystemApi.loop()
        } catch { case _: InterruptedException => }
      } catch { case _: InterruptedException => }
  }

  private[this] var root: Path = _

  @inline
  private[this] def setRoot(path: Path): Unit = {
    if (root == null) root = path
    if (root startsWith path) {
      root = path
    } else if (!(path startsWith root)) {
      val prefix = parts(path).view.zip(parts(root).view).takeWhile { case (l, r) => l == r }
      root = new File(prefix.map(_._1).mkString(File.separator, File.separator, "")).toPath
    }
  }

  @inline
  private[this] def parts(path: Path) = path.toString.split(File.separatorChar).drop(1)

  @tailrec @inline
  private[this] def alreadyWatching(path: Path): Boolean = {
    if (path == root || path.toString == File.separator) false
    else (streams contains path) || alreadyWatching(path.getParent)
  }
  @inline
  private[this] def submit[R](e: ExecutorService)(f: => R): Unit = e.submit(() => f)
}
