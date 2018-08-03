package com.swoval.files

import com.swoval.files.PathWatchers.Event
import com.swoval.functional.Consumer

private[files] object PlatformWatcher {
  def make(followLinks: Boolean,
           registerable: RegisterableWatchService,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    val watcher = new NioPathWatcher(directoryRegistry, registerable, followLinks)
    if (followLinks) new SymlinkFollowingPathWatcher(watcher, directoryRegistry) else watcher
  }
  def make(followLinks: Boolean,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    make(followLinks, null, directoryRegistry)
  }
}
