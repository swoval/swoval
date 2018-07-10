package com.swoval.files

import com.swoval.files.PathWatchers.Event
import com.swoval.functional.Consumer

private[files] object PlatformWatcher {
  def make(registerable: RegisterableWatchService,
           directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    new NioPathWatcher(directoryRegistry, registerable)
  }
  def make(directoryRegistry: DirectoryRegistry): PathWatcher[PathWatchers.Event] = {
    make(null, directoryRegistry)
  }
}
