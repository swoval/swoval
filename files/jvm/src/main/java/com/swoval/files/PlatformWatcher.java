package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import com.swoval.logging.Logger;
import java.io.IOException;

class PlatformWatcher {
  static PathWatcher<Event> make(
      final boolean followLinks, final DirectoryRegistry directoryRegistry, final Logger logger)
      throws InterruptedException, IOException {
    return make(followLinks, RegisterableWatchServices.get(), directoryRegistry, logger);
  }

  static PathWatcher<Event> make(
      final boolean followLinks,
      final RegisterableWatchService registerableWatchService,
      final DirectoryRegistry directoryRegistry,
      final Logger logger)
      throws InterruptedException, IOException {
    final PathWatcher<Event> pathWatcher =
        new NioPathWatcher(directoryRegistry, registerableWatchService, logger);
    return followLinks
        ? new SymlinkFollowingPathWatcher(pathWatcher, directoryRegistry, logger)
        : pathWatcher;
  }
}
