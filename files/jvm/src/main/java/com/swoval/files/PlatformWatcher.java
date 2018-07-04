package com.swoval.files;

import com.swoval.files.PathWatcher.Event;
import com.swoval.functional.Consumer;
import java.io.IOException;

public class PlatformWatcher {
  static PathWatcher make(
      final Consumer<Event> callback,
      final Executor executor,
      final DirectoryRegistry directoryRegistry,
      final PathWatcher.Option... options)
      throws IOException, InterruptedException {
    return make(callback, RegisterableWatchService.newWatchService(), executor, directoryRegistry, options);
  }

  static PathWatcher make(
      final Consumer<Event> callback,
      final Registerable registerable,
      final Executor executor,
      final DirectoryRegistry directoryRegistry,
      final PathWatcher.Option... options)
      throws InterruptedException {
    return make(
        callback,
        registerable,
        Executor.make("com.swoval.files.NioPathWatcher-callback-thread"),
        executor,
        directoryRegistry,
        options);
  }

  static PathWatcher make(
      final Consumer<Event> callback,
      final Registerable registerable,
      final Executor callbackExecutor,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry,
      final PathWatcher.Option... options)
      throws InterruptedException {
    return new NioPathWatcherImpl(
        callback, registerable, callbackExecutor, internalExecutor, directoryRegistry, options);
  }
}
