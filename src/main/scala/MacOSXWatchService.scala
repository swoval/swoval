package com.swoval.watchservice

import io.methvin.watchservice.{ MacOSXListeningWatchService, MacOSXWatchKey, WatchablePath }
import java.util.List
import java.util.concurrent.TimeUnit
import java.nio.file.{
  ClosedWatchServiceException,
  Watchable,
  WatchEvent,
  WatchKey,
  Path
}
import sbt.io.WatchService
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * WatchService for Mac OS X to replace default PollingWatchService.
 *
 * The implementation is almost copied from sbt.io.WatchService.WatchServiceAdapter.
 */
object MacOSXWatchService {
  private class WatchKeyWrapper(path: Path, key: WatchKey) extends WatchKey {
    def cancel(): Unit = key.cancel
    def isValid(): Boolean = key.isValid
    def pollEvents(): List[java.nio.file.WatchEvent[_]] = key.pollEvents()
    def reset(): Boolean = key.reset()
    def watchable(): Watchable = path
  }
}
final class MacOSXWatchService extends WatchService {
  private val service = new MacOSXListeningWatchService
  private var closed: Boolean = false
  private val registered: mutable.Buffer[WatchKey] = mutable.Buffer.empty

  override def init(): Unit =
    ()

  override def pollEvents(): Map[WatchKey, Seq[WatchEvent[Path]]] = {
    registered.flatMap { k =>
      val events = k.pollEvents()
      if (events.isEmpty) None
      else Some((k, events.asScala.asInstanceOf[Seq[WatchEvent[Path]]]))
    }.toMap
  }

  @tailrec
  override def poll(timeout: Duration): WatchKey = if (timeout.isFinite) {
    service.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
  } else {
    service.poll(1000L, TimeUnit.MILLISECONDS) match {
      case null => poll(timeout)
      case key  => key
    }
  }

  override def register(path: Path, events: WatchEvent.Kind[Path]*): WatchKey = {
    if (closed) throw new ClosedWatchServiceException
    else {
      val key = new WatchablePath(path).register(service, events: _*)
      registered += new MacOSXWatchService.WatchKeyWrapper(path, key)
      key
    }
  }

  override def close(): Unit = {
    closed = true
    service.close()
  }

  override def toString(): String =
    service.toString()
}

