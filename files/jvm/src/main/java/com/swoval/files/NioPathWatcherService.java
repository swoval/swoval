package com.swoval.files;

import static com.swoval.files.Entries.UNKNOWN;
import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Overflow;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.logging.Loggers.Level;
import com.swoval.runtime.ShutdownHooks;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class WatchedDirectoriesByPath extends LockableMap<Path, WatchedDirectory> {}

class NioPathWatcherService implements AutoCloseable {
  private final Thread loopThread;
  private final AtomicBoolean isStopped = new AtomicBoolean(false);
  private static final AtomicInteger threadId = new AtomicInteger(0);
  private final AtomicBoolean isShutdown = new AtomicBoolean(false);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final RegisterableWatchService watchService;
  private final WatchedDirectoriesByPath watchedDirectoriesByPath = new WatchedDirectoriesByPath();
  private final int shutdownHookId;
  private final Logger logger;

  NioPathWatcherService(
      final Consumer<Either<Overflow, Event>> eventConsumer,
      final RegisterableWatchService watchService,
      final Logger logger)
      throws InterruptedException {
    this.watchService = watchService;
    this.logger = logger;
    this.shutdownHookId =
        ShutdownHooks.addHook(
            1,
            new Runnable() {
              @Override
              public void run() {
                close();
              }
            });
    final CountDownLatch latch = new CountDownLatch(1);
    final String prefix = this.toString();
    loopThread =
        new Thread("NioPathWatcher-loop-thread-" + threadId.incrementAndGet()) {
          @Override
          public void run() {
            latch.countDown();
            boolean stop = false;
            while (!isStopped.get() && !stop && !Thread.currentThread().isInterrupted()) {
              try {
                final WatchKey key = watchService.take();
                final List<WatchEvent<?>> events = key.pollEvents();
                if (!key.reset()) {
                  key.cancel();
                }
                final Iterator<WatchEvent<?>> it = events.iterator();
                while (it.hasNext()) {
                  final WatchEvent<?> e = it.next();
                  final WatchEvent.Kind<?> k = e.kind();
                  if (Loggers.shouldLog(logger, Level.DEBUG))
                    logger.debug(
                        prefix + " received event for path " + e.context() + " with kind " + k);
                  if (OVERFLOW.equals(k)) {
                    final Either<Overflow, Event> result =
                        Either.left(new Overflow((Path) key.watchable()));
                    eventConsumer.accept(result);
                  } else if (k != null) {
                    final Event.Kind kind =
                        k.equals(ENTRY_DELETE) ? Delete : k.equals(ENTRY_CREATE) ? Create : Modify;
                    final Path watchKey = (Path) key.watchable();
                    final Path path =
                        e.context() == null ? watchKey : watchKey.resolve((Path) e.context());
                    final Either<Overflow, Event> result =
                        Either.right(new Event(TypedPaths.get(path, UNKNOWN), kind));
                    eventConsumer.accept(result);
                  }
                }
              } catch (final ClosedWatchServiceException | InterruptedException e) {
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

  private final class CachedWatchDirectory implements WatchedDirectory {
    private final Path path;
    private final WatchKey key;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    CachedWatchDirectory(final Path path) throws IOException {
      this.path = path;
      this.key = watchService.register(path, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    @Override
    public void close() {
      if (!isShutdown.get() && closed.compareAndSet(false, true)) {
        if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " stopping watch");
        watchedDirectoriesByPath.remove(path);
        key.reset();
        key.cancel();
      }
    }

    @Override
    public String toString() {
      return "WatchedDirectory(" + path + ")";
    }
  }

  Either<IOException, WatchedDirectory> register(final Path path) {
    Either<IOException, WatchedDirectory> result;
    if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " registering " + path);
    try {
      if (watchedDirectoriesByPath.lock()) {
        try {
          final WatchedDirectory previousWatchedDirectory = watchedDirectoriesByPath.get(path);
          if (previousWatchedDirectory == null) {
            final WatchedDirectory watchedDirectory = new CachedWatchDirectory(path);
            watchedDirectoriesByPath.put(path, watchedDirectory);
            if (Loggers.shouldLog(logger, Level.DEBUG))
              logger.debug(this + " creating new watch key for " + path);
            result = Either.right(watchedDirectory);
          } else {
            if (Loggers.shouldLog(logger, Level.DEBUG))
              logger.debug(this + " using existing watch key for " + path);
            result = Either.right(previousWatchedDirectory);
          }
        } finally {
          watchedDirectoriesByPath.unlock();
        }
      } else {
        result = Either.right(null);
      }
    } catch (final ClosedWatchServiceException e) {
      result = Either.left(new IOException(e));
    } catch (final IOException e) {
      result = Either.left(e);
    }
    if (Loggers.shouldLog(logger, Level.DEBUG))
      logger.debug(
          this
              + (" registration for " + path + " ")
              + (result.isLeft() ? "failed (" + result + ")" : "succeeded"));
    return result;
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    if (isStopped.compareAndSet(false, true)) {
      ShutdownHooks.removeHook(shutdownHookId);
      loopThread.interrupt();
      try {
        final Iterator<WatchedDirectory> it = watchedDirectoriesByPath.values().iterator();
        while (it.hasNext()) {
          it.next().close();
        }
        isShutdown.set(true);
        watchService.close();
        shutdownLatch.await(5, TimeUnit.SECONDS);
        loopThread.join(5000);
      } catch (final InterruptedException | IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
