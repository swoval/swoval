package com.swoval.files

import java.util.concurrent.atomic.AtomicInteger

import com.swoval.files.Callbacks.Callback
import com.swoval.files.Directory.PathConverter

import scala.collection.mutable

trait Callbacks[P <: Path] extends AutoCloseable {
  def addCallback(callback: Callback[P]): Int = {
    val key = counter.getAndIncrement()
    lock.synchronized(callbacks += key -> callback)
    key
  }

  def removeCallback(handle: Int): Unit = {
    lock.synchronized(callbacks -= handle)
  }

  private[this] object lock

  private[this] val counter = new AtomicInteger(0)
  private[this] val callbacks = mutable.Map.empty[Int, Callback[P]]
  final val callback: Callback[P] = fe => {
    val cbs = lock.synchronized(callbacks.values)
    cbs.foreach(_.apply(fe))
  }

  override def close(): Unit = {
    callbacks.clear()
  }
}
object Callbacks {
  type Callback[P <: Path] = FileWatchEvent[P] => Unit
  def apply[P <: Path](): Callback[P] with Callbacks[P] =
    new Callback[P] with Callbacks[P] {
      override def apply(fe: FileWatchEvent[P]): Unit = callback.apply(fe)
    }
}
