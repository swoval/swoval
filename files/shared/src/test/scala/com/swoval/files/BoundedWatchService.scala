package com.swoval.files
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.{ WatchEvent, WatchKey, Watchable, Path }
import java.util
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

class BoundedWatchService(val queueSize: Int, underlying: Registerable)
    extends RegisterableWatchService(underlying) {
  override def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey = {
    val underlying = super.register(path, kinds: _*)
    new BoundedWatchKey(queueSize, underlying)
  }

  override def poll(): WatchKey = super.poll() match {
    case null => null
    case key  => new BoundedWatchKey(queueSize, key)
  }

  override def poll(timeout: Long, unit: TimeUnit): WatchKey = super.poll(timeout, unit) match {
    case null => null
    case key  => new BoundedWatchKey(queueSize, key)
  }

  override def take(): WatchKey = new BoundedWatchKey(queueSize, super.take())
}
private class BoundedWatchKey(val queueSize: Int, underlying: WatchKey) extends WatchKey {
  override def isValid: Boolean = underlying.isValid

  override def pollEvents(): util.List[WatchEvent[_]] = {
    val raw = underlying.pollEvents
    if (raw.size() > queueSize) {
      val result = raw.asScala
        .take(queueSize)
        .asJava
      result.add(new OverflowWatchEvent)
      result
    } else raw
  }

  override def reset(): Boolean = underlying.reset()

  override def cancel(): Unit = underlying.cancel()

  override def watchable(): Watchable = underlying.watchable
}
private class OverflowWatchEvent[T >: Null <: AnyRef] extends WatchEvent[T] {
  override def kind(): WatchEvent.Kind[T] = OVERFLOW.asInstanceOf[WatchEvent.Kind[T]]
  override def count(): Int = 1
  override def context(): T = null
}
