package com.swoval.files

import java.nio.file.{ Path, WatchEvent, WatchKey, WatchService => JWatchService }
import java.util.concurrent.TimeUnit

private[files] object RegisterableWatchService {
  def newWatchService(): Registerable = new Registerable {
    override def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey = null
    override def close(): Unit = {}
    override def poll(): WatchKey = null
    override def poll(timeout: Long, unit: TimeUnit): WatchKey = null
    override def take(): WatchKey = null
  }
}
private[files] class RegisterableWatchService(underlying: JWatchService)
    extends JWatchService
    with Registerable {
  override def close(): Unit = {}
  override def poll(): WatchKey = null
  override def poll(timeout: Long, unit: TimeUnit): WatchKey = null
  override def take(): WatchKey = null
  override def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey = null
}
