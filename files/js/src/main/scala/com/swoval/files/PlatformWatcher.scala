package com.swoval.files

import com.swoval.files.PathWatchers.Event
import com.swoval.functional.Consumer

object PlatformWatcher {
  def make(callback: Consumer[Event],
           registerable: Registerable,
           callbackExecutor: Executor,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher = {
    new NioPathWatcher(callback,
                       registerable,
                       callbackExecutor,
                       internalExecutor,
                       directoryRegistry)
  }
  def make(callback: Consumer[Event],
           registerable: Registerable,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher = {
    make(callback, registerable, Executor.make("callback"), internalExecutor, directoryRegistry)
  }
  def make(callback: Consumer[Event],
           executor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher = {
    make(callback, null, executor, directoryRegistry)
  }
}
