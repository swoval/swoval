package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import java.io.IOException;

class PlatformWatcher {
  static PathWatcher<Event> make(final DirectoryRegistry directoryRegistry)
      throws IOException, InterruptedException {
    return make(RegisterableWatchServices.get(), directoryRegistry);
  }

  static PathWatcher<Event> make(
      final RegisterableWatchService registerableWatchService,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    return new NioPathWatcher(directoryRegistry, registerableWatchService);
  }
}
