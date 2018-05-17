package com.swoval.files

import com.swoval.files.Directory.Entry
import com.swoval.files.Directory.Observer
import com.swoval.files.Directory.OnChange
import com.swoval.files.Directory.OnUpdate
import java.util.Collection
import java.util.HashMap
import java.util.Iterator
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger
import Observers._

private[files] object Observers {

  def apply[T](onchange: OnChange[T]): Observer[T] = new Observer[T]() {
    override def onCreate(newEntry: Entry[T]): Unit = {
      onchange.apply(newEntry)
    }

    override def onDelete(oldEntry: Entry[T]): Unit = {
      onchange.apply(oldEntry)
    }

    override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
      onchange.apply(newEntry)
    }
  }

  def apply[T](oncreate: OnChange[T], onupdate: OnUpdate[T], ondelete: OnChange[T]): Observer[T] =
    new Observer[T]() {
      override def onCreate(newEntry: Entry[T]): Unit = {
        oncreate.apply(newEntry)
      }

      override def onDelete(oldEntry: Entry[T]): Unit = {
        ondelete.apply(oldEntry)
      }

      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
        onupdate.apply(oldEntry, newEntry)
      }
    }

}

/**
 * Container class that wraps multiple [[Observer]] and runs the callbacks for each whenever the
 * [[FileCache]] detects an event.
 * @tparam T The data type for the [[FileCache]] to which the observers correspond
 */
private[files] class Observers[T] extends Observer[T] with AutoCloseable {

  private val counter: AtomicInteger = new AtomicInteger(0)

  private val lock: AnyRef = new AnyRef()

  private val observers: Map[Integer, Observer[T]] = new HashMap()

  override def onCreate(newEntry: Entry[T]): Unit = {
    var cbs: Collection[Observer[T]] = null
    lock.synchronized {
      cbs = observers.values
    }
    val it: Iterator[Observer[T]] = cbs.iterator()
    while (it.hasNext) it.next().onCreate(newEntry)
  }

  override def onDelete(oldEntry: Entry[T]): Unit = {
    var cbs: Collection[Observer[T]] = null
    lock.synchronized {
      cbs = observers.values
    }
    val it: Iterator[Observer[T]] = cbs.iterator()
    while (it.hasNext) it.next().onDelete(oldEntry)
  }

  override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
    var cbs: Collection[Observer[T]] = null
    lock.synchronized {
      cbs = observers.values
    }
    val it: Iterator[Observer[T]] = cbs.iterator()
    while (it.hasNext) it.next().onUpdate(oldEntry, newEntry)
  }

  def addObserver(observer: Observer[T]): Int = {
    val key: Int = counter.getAndIncrement
    lock.synchronized {
      observers.put(key, observer)
    }
    key
  }

  def removeObserver(handle: Int): Unit = {
    lock.synchronized {
      observers.remove(handle)
    }
  }

  override def close(): Unit = {
    observers.clear()
  }

}
