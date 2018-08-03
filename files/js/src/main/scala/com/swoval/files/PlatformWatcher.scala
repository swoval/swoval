package com.swoval.files

private[files] object PlatformWatcher {
  def make(followLinks: Boolean,
           registerable: RegisterableWatchService,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    val watcher = new NioPathWatcher(directoryRegistry, registerable)
    if (followLinks) new SymlinkFollowingPathWatcher(watcher, directoryRegistry) else watcher
  }
  def make(followLinks: Boolean,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    make(followLinks, null, directoryRegistry)
  }
}
