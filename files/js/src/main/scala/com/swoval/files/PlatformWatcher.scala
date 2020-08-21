package com.swoval.files
import com.swoval.logging.Logger

private[files] object PlatformWatcher {
  def make(
      followLinks: Boolean,
      registerable: RegisterableWatchService,
      directoryRegistry: DirectoryRegistry,
      logger: Logger
  ): PathWatcher[PathWatchers.Event] = {
    val watcher = new NioPathWatcher(directoryRegistry, registerable, logger)
    if (followLinks) new SymlinkFollowingPathWatcher(watcher, directoryRegistry, logger)
    else watcher
  }
  def make(
      followLinks: Boolean,
      directoryRegistry: DirectoryRegistry,
      logger: Logger
  ): PathWatcher[PathWatchers.Event] = {
    make(followLinks, null, directoryRegistry, logger)
  }
}
