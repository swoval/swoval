package com.swoval.files

import java.nio.file._
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.util
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import TestHelpers._

class BoundedWatchService(val queueSize: Int, underlying: RegisterableWatchService)
    extends RegisterableWatchService {
  override def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey = {
    new BoundedWatchKey(queueSize, underlying.register(path, kinds: _*))
  }

  override def poll(): WatchKey = underlying.poll() match {
    case null => null
    case key  => new BoundedWatchKey(queueSize, key)
  }

  override def poll(timeout: Long, unit: TimeUnit): WatchKey =
    underlying.poll(timeout, unit) match {
      case null => null
      case key  => new BoundedWatchKey(queueSize, key)
    }

  override def close(): Unit = underlying.close()

  override def take(): WatchKey = underlying.take() match {
    case null => null
    case key  => new BoundedWatchKey(queueSize, key)
  }
}
private class BoundedWatchKey(val queueSize: Int, underlying: WatchKey) extends WatchKey {
  override def isValid: Boolean = underlying.isValid

  override def pollEvents(): util.List[WatchEvent[_]] = {
    //underlying.pollEvents()
    0.milliseconds.sleep // This makes it more likely that an overflow is triggered
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
