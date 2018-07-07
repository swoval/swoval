package com.swoval.files;

import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import java.io.IOException;

class PlatformWatcher {
  static PathWatcher make(
      final Consumer<Event> callback,
      final Executor executor,
      final DirectoryRegistry directoryRegistry)
      throws IOException, InterruptedException {
    return make(callback, RegisterableWatchService.newWatchService(), executor, directoryRegistry);
  }

  static PathWatcher make(
      final Consumer<Event> callback,
      final Registerable registerable,
      final Executor executor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    return make(
        callback,
        registerable,
        Executor.make("com.swoval.files.NioPathWatcher-callback-thread"),
        executor,
        directoryRegistry);
  }

  static PathWatcher make(
      final Consumer<Event> callback,
      final Registerable registerable,
      final Executor callbackExecutor,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    return new NioPathWatcher(
        callback, registerable, callbackExecutor, internalExecutor, directoryRegistry);
  }
}
