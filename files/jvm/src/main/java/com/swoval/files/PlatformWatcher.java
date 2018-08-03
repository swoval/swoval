package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import java.io.IOException;

class PlatformWatcher {
  static PathWatcher<Event> make(
      final boolean followLinks, final DirectoryRegistry directoryRegistry)
      throws InterruptedException, IOException {
    return make(followLinks, RegisterableWatchServices.get(), directoryRegistry);
  }

  static PathWatcher<Event> make(
      final boolean followLinks,
      final RegisterableWatchService registerableWatchService,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException, IOException {
    final PathWatcher<Event> pathWatcher =
        new NioPathWatcher(directoryRegistry, registerableWatchService, followLinks);
    return followLinks
        ? new SymlinkFollowingPathWatcher(pathWatcher, directoryRegistry)
        : pathWatcher;
  }
}
