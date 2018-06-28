package com.swoval.files

import com.swoval.files.DirectoryWatcher.Event
import com.swoval.functional.Consumer
object PlatformWatcher {
  def make(callback: Consumer[Event],
           registerable: Registerable,
           callbackExecutor: Executor,
           internalExecutor: Executor): DirectoryWatcher = {
    new NioDirectoryWatcherImpl(callback, callbackExecutor, internalExecutor)
  }
  def make(callback: Consumer[Event],
           registerable: Registerable,
           internalExecutor: Executor): DirectoryWatcher = {
    make(callback, registerable, Executor.make("callback"), internalExecutor)
  }
  def make(callback: Consumer[Event], executor: Executor): DirectoryWatcher = {
    make(callback, null, executor)
  }
}
