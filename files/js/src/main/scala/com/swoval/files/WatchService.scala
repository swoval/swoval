package com.swoval.files
import java.nio.file.{ Path, WatchEvent, WatchKey }
import java.util.concurrent.TimeUnit

private[files] object WatchService {
  def newWatchService(): Registerable = new Registerable {
    override def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey = null
    override def close(): Unit = {}
    override def poll(): WatchKey = null
    override def poll(timeout: Long, unit: TimeUnit): WatchKey = null
    override def take(): WatchKey = null
  }
}
