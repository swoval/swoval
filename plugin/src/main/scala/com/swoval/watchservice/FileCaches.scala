package com.swoval.watchservice

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files._

object FileCaches {
  def ex = Executor.make("com.swoval.files.FileCaches.executor-thread")
  object default extends FileCacheImpl(FileOptions.default, NoMonitor, ex)(Callbacks)
  lazy val NoCache: FileCache = new NoCache(FileOptions.default, NoMonitor, Callbacks)
  def noCache(fileOptions: FileOptions): FileCache = new NoCache(fileOptions, NoMonitor, Callbacks)
  def noCache(fileOptions: FileOptions, dirOptions: DirectoryOptions): FileCache =
    new NoCache(fileOptions, dirOptions, Callbacks)
  def apply(fileOptions: FileOptions) = new FileCacheImpl(fileOptions, NoMonitor, ex)(Callbacks)
  def apply(dirOptions: DirectoryOptions) = new FileCacheImpl(NoMonitor, dirOptions, ex)(Callbacks)
  def apply(fileOptions: FileOptions, dirOptions: DirectoryOptions)(callback: Callback) =
    new FileCacheImpl(fileOptions, dirOptions, ex)(callback)
}
