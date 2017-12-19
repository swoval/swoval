package com.swoval.watchservice.files

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY }

import com.swoval.watchservice.files.Directory.{ Callback, FileEvent }

import scala.collection.mutable

class Callbacks extends Callback {
  private[this] val callbacks = mutable.Set.empty[Callback]
  def add(callback: Callback) = callbacks.synchronized(callbacks += callback)
  def remove(callback: Callback) = callbacks.synchronized(callbacks -= callback)
  def apply(e: FileEvent[_]) = callbacks.synchronized(callbacks.toIndexedSeq) foreach (_.apply(e))
  def apply(e: com.swoval.watcher.FileEvent): Unit = {
    val kind = e match {
      case fe if fe.isRemoved => ENTRY_DELETE
      case fe if fe.isNewFile => ENTRY_CREATE
      case _                  => ENTRY_MODIFY
    }
    apply(FileEvent(new File(e.fileName).toPath, kind))
  }
}
object Callbacks extends Callbacks
