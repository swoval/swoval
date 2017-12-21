package com.swoval.watchservice

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import com.swoval.files.apple.{ FileEvent => AppleFileEvent }
import com.swoval.files.{ FileWatchEvent, Path }

import scala.collection.mutable

object Callbacks extends Callback {
  private[this] val callbacks = mutable.Set.empty[Callback]
  def add(callback: Callback) = callbacks.synchronized(callbacks += callback)
  def remove(callback: Callback) = callbacks.synchronized(callbacks -= callback)
  def apply(e: FileWatchEvent) = callbacks.synchronized(callbacks.toIndexedSeq) foreach (_.apply(e))
  def apply(e: AppleFileEvent): Unit = {
    val kind = e match {
      case fe if fe.isRemoved => Delete
      case fe if fe.isNewFile => Create
      case _                  => Modify
    }
    apply(FileWatchEvent(Path(e.fileName), kind))
  }
}
