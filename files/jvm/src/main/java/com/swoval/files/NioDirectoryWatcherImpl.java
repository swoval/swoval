package com.swoval.files;

import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Delete;
import static com.swoval.files.DirectoryWatcher.Event.Modify;
import static com.swoval.files.DirectoryWatcher.Event.Overflow;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.IO;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class NioDirectoryWatcherImpl extends NioDirectoryWatcher {
  private final Thread loopThread;
  private final AtomicBoolean isStopped = new AtomicBoolean(false);
  private static final AtomicInteger threadId = new AtomicInteger(0);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final Registerable watchService;

  NioDirectoryWatcherImpl(
      final Consumer<Event> callback,
      final Registerable watchService,
      final Executor callbackExecutor,
      final Executor executor,
      final DirectoryWatcher.Option... options)
      throws InterruptedException {
    super(
        new IO<Path, WatchedDirectory>() {
          final Map<Path, WatchedDirectory> watchedDirectoriesByPath = new HashMap<>();

          @Override
          public Either<IOException, WatchedDirectory> apply(final Path path) {
            Either<IOException, WatchedDirectory> result;
            try {
              final WatchedDirectory previousWatchedDirectory = watchedDirectoriesByPath.get(path);
              if (previousWatchedDirectory == null) {
                final WatchedDirectory watchedDirectory =
                    new WatchedDirectory() {
                      private final WatchKey key =
                          watchService.register(path, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                      private final AtomicBoolean closed = new AtomicBoolean(false);

                      @Override
                      public boolean isValid() {
                        return true;
                      }

                      @Override
                      public void reset() {
                        if (!key.reset()) {
                          key.cancel();
                        }
                      }

                      @Override
                      public void close() {
                        if (closed.compareAndSet(false, true)) {
                          key.cancel();
                          key.reset();
                        }
                      }
                    };
                watchedDirectoriesByPath.put(path, watchedDirectory);
                result = Either.right(watchedDirectory);
              } else {
                result = Either.right(previousWatchedDirectory);
              }
            } catch (ClosedWatchServiceException e) {
              result = Either.left(new IOException(e));
            } catch (IOException e) {
              result = Either.left(e);
            }
            return result;
          }
        },
        callbackExecutor,
        executor,
        options);
    this.watchService = watchService;
    final CountDownLatch latch = new CountDownLatch(1);
    loopThread =
        new Thread("NioDirectoryWatcher-loop-thread-" + threadId.incrementAndGet()) {
          @Override
          public void run() {
            latch.countDown();
            boolean stop = false;
            while (!isStopped.get() && !stop && !Thread.currentThread().isInterrupted()) {
              try {
                final WatchKey key = watchService.take();
                boolean submitted = false;
                while (!submitted) {
                  try {
                    executor.run(
                        new Runnable() {
                          @Override
                          public void run() {
                            final List<WatchEvent<?>> events = key.pollEvents();
                            final Iterator<WatchEvent<?>> it = events.iterator();
                            if (!key.reset()) {
                              key.cancel();
                              // watchedDirs.remove((Path) key.watchable());
                            }
                            while (it.hasNext()) {
                              final WatchEvent<?> e = it.next();
                              final WatchEvent.Kind<?> k = e.kind();
                              final DirectoryWatcher.Event.Kind kind =
                                  k.equals(ENTRY_DELETE)
                                      ? Delete
                                      : k.equals(ENTRY_CREATE)
                                          ? Create
                                          : k.equals(OVERFLOW) ? Overflow : Modify;
                              final Path watchKey = (Path) key.watchable();
                              if (!kind.equals(Overflow) || !Files.exists(watchKey)) {
                                handleEvent(callback, watchKey.resolve((Path) e.context()), kind);
                              } else {
                                handleOverflow(callback, watchKey);
                              }
                            }
                          }
                        });
                    submitted = true;
                  } catch (RejectedExecutionException e) {
                    try {
                      Thread.sleep(2);
                    } catch (InterruptedException ex) {
                    }
                  }
                }
              } catch (ClosedWatchServiceException | InterruptedException e) {
                stop = true;
              }
            }
            shutdownLatch.countDown();
          }
        };
    loopThread.setDaemon(true);
    loopThread.start();
    latch.await(5, TimeUnit.SECONDS);
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    if (isStopped.compareAndSet(false, true)) {
      loopThread.interrupt();
      super.close();
      try {
        watchService.close();
        shutdownLatch.await(5, TimeUnit.SECONDS);
        loopThread.join(5000);
      } catch (InterruptedException | IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
