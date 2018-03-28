package com.swoval.files

import java.util.concurrent.atomic.AtomicInteger

import com.swoval.files.DirectoryWatcher.Callback

import scala.collection.mutable

trait Callbacks extends AutoCloseable {
  def addCallback(callback: Callback): Int = {
    val key = counter.getAndIncrement()
    lock.synchronized(callbacks += key -> callback)
    key
  }

  def removeCallback(handle: Int): Unit = {
    lock.synchronized(callbacks -= handle)
  }

  private[this] object lock

  private[this] val counter = new AtomicInteger(0)
  private[this] val callbacks = mutable.Map.empty[Int, Callback]
  final val callback: Callback = fe => {
    val cbs = lock.synchronized(callbacks.values)
    cbs.foreach(_.apply(fe))
  }

  override def close(): Unit = {
    callbacks.clear()
  }
}
object Callbacks {
  def apply(): Callback with Callbacks = new Callback with Callbacks {
    override def apply(fe: FileWatchEvent): Unit = callback.apply(fe)
  }
}
