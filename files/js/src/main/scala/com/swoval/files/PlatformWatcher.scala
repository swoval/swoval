package com.swoval.files

import com.swoval.files.PathWatchers.Event
import com.swoval.functional.Consumer

private[files] object PlatformWatcher {
  def make(registerable: RegisterableWatchService,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    new NioPathWatcher(directoryRegistry, registerable, internalExecutor)
  }
  def make(executor: Executor,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    make(null, executor, directoryRegistry)
  }
}
