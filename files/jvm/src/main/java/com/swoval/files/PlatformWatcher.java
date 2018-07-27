package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import java.io.IOException;

class PlatformWatcher {
  static PathWatcher<Event> make(
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws IOException, InterruptedException {
    return make(RegisterableWatchServices.get(), internalExecutor, directoryRegistry);
  }

  static PathWatcher<Event> make(
      final RegisterableWatchService registerableWatchService,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    return new NioPathWatcher(directoryRegistry, registerableWatchService, internalExecutor);
  }
}
