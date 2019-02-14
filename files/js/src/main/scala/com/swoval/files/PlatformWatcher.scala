package com.swoval.files
import com.swoval.logging.Logger

private[files] object PlatformWatcher {
  def make(registerable: RegisterableWatchService,
           directoryRegistry: DirectoryRegistry,
           logger: Logger): PathWatcher[PathWatchers.Event] = {
    new NioPathWatcher(directoryRegistry, registerable, logger)
  }
  def make(directoryRegistry: DirectoryRegistry,
           logger: Logger): PathWatcher[PathWatchers.Event] = {
    make(null, directoryRegistry, logger)
  }
}
