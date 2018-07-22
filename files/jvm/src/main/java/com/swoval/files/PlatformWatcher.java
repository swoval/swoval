package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import java.io.IOException;

class PlatformWatcher {
  static PathWatcher<Event> make(
      final BiConsumer<Event, Executor.Thread> callback,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws IOException, InterruptedException {
    return make(callback, WatchServices.get(), internalExecutor, directoryRegistry);
  }

  static PathWatcher<Event> make(
      final BiConsumer<Event, Executor.Thread> callback,
      final RegisterableWatchService registerableWatchService,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
        return new NioPathWatcher(
            directoryRegistry,
            registerableWatchService,
            new Consumer<Event>() {
              @Override
              public void accept(final Event event) {
                internalExecutor.run(
                    new Consumer<Executor.Thread>() {
                      @Override
                      public void accept(Executor.Thread thread) {
                        callback.accept(event, thread);
                      }
                    });
              }
            },
            internalExecutor);
  }
}
