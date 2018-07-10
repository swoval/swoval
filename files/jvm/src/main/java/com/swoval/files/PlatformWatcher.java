package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import java.io.IOException;

class PlatformWatcher {
  static ManagedPathWatcher make(
      final BiConsumer<Event, Executor.Thread> callback,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws IOException, InterruptedException {
    return make(callback, WatchServices.get(), internalExecutor, directoryRegistry);
  }

  static ManagedPathWatcher make(
      final BiConsumer<Event, Executor.Thread> callback,
      final RegisterableWatchService registerableWatchService,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    final Observers<PathWatchers.Event> observers = new Observers<>();
    final NioPathWatcherDirectoryTree tree =
        new NioPathWatcherDirectoryTree(
            observers,
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
    return new NioPathWatcher(tree, internalExecutor);
  }
}
