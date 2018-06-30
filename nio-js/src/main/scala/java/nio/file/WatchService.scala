package java.nio.file

import java.io.Closeable
import java.util.concurrent.TimeUnit

trait WatchService extends Closeable {
  def close(): Unit = {}
  def poll(): WatchKey
  def poll(timeout: Long, unit: TimeUnit): WatchKey
  def take(): WatchKey
}

trait WatchKey {
  def isValid(): Boolean
  def pollEvents(): List[WatchEvent[_]]
  def reset(): Boolean
  def cancel(): Unit
  def watchable(): Watchable
}

trait WatchEvent[T] {
  def kind(): WatchEvent.Kind[T]
  def count(): Int
  def context(): T
}
object WatchEvent {
  trait Modifier {
    def name(): String
  }
  trait Kind[T] {
    def name(): String
    def `type`(): Class[T]
  }
}
