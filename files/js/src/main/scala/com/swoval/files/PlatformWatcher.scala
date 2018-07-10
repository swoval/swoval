package com.swoval.files

import com.swoval.files.PathWatchers.Event
import com.swoval.functional.Consumer

private[files] object PlatformWatcher {
  def make(callback: Consumer[Event],
           registerable: RegisterableWatchService,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher = {
    new NioPathWatcher(callback, registerable, internalExecutor, directoryRegistry)
  }
  def make(callback: Consumer[Event],
           executor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher = {
    make(callback, null, executor, directoryRegistry)
  }
}
