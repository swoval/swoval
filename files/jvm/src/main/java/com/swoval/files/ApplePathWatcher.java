package com.swoval.files;

import static com.swoval.files.PathWatcher.Event.Create;
import static com.swoval.files.PathWatcher.Event.Delete;
import static com.swoval.files.PathWatcher.Event.Modify;
import static com.swoval.files.PathWatcher.Event.Overflow;

import com.swoval.files.apple.FileEvent;
import com.swoval.files.apple.FileEventsApi;
import com.swoval.files.apple.FileEventsApi.ClosedFileEventsApiException;
import com.swoval.files.apple.Flags;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the PathWatcher for Mac OSX using the <a
 * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html"
 * target="_blank">Apple File System Events Api</a>
 */
public class ApplePathWatcher extends PathWatcher {
  private final DirectoryRegistry directoryRegistry;
  private final Map<Path, Stream> streams = new HashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final double latency;
  private final Executor callbackExecutor;
  private final Executor internalExecutor;
  private final Flags.Create flags;
  private final FileEventsApi fileEventsApi;
  private static final DefaultOnStreamRemoved DefaultOnStreamRemoved = new DefaultOnStreamRemoved();

  private static class Stream {
    public final int id;

    Stream(final int id) {
      this.id = id;
    }
  }

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    return register(path, flags, maxDepth);
  }

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags {@link com.swoval.files.apple.Flags.Create} to set for the directory
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  public Either<IOException, Boolean> register(
      final Path path, final Flags.Create flags, final int maxDepth) {
    final Either<Exception, Boolean> either =
        internalExecutor.block(
            new Callable<Boolean>() {
              @Override
              public Boolean call() {
                return registerImpl(path, flags, maxDepth);
              }
            });
    if (either.isLeft() && !(either.left().getValue() instanceof IOException)) {
      throw new RuntimeException(either.left().getValue());
    }
    return either.castLeft(IOException.class);
  }

  private boolean registerImpl(final Path path, final Flags.Create flags, final int maxDepth) {
    boolean result = true;
    Path realPath = path;
    try {
      realPath = path.toRealPath();
    } catch (IOException e) {
    }
    final Entry<Path, Stream> entry = find(realPath);
    directoryRegistry.addDirectory(path, maxDepth);
    if (entry == null) {
      try {
        int id = fileEventsApi.createStream(realPath.toString(), latency, flags.getValue());
        if (id == -1) {
          result = false;
          System.err.println("Error watching " + realPath + ".");
        } else {
          removeRedundantStreams(realPath);
          streams.put(realPath, new Stream(id));
        }
      } catch (ClosedFileEventsApiException e) {
        close();
        result = false;
      }
    }
    return result;
  }

  private void removeRedundantStreams(final Path path) {
    final List<Path> toRemove = new ArrayList<>();
    final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
    while (it.hasNext()) {
      final Entry<Path, Stream> e = it.next();
      final Path key = e.getKey();
      if (key.startsWith(path) && !key.equals(path)) {
        toRemove.add(key);
      }
    }
    final Iterator<Path> pathIterator = toRemove.iterator();
    while (pathIterator.hasNext()) {
      unregisterImpl(pathIterator.next());
    }
  }

  private void unregisterImpl(final Path path) {
    if (!closed.get()) {
      directoryRegistry.removeDirectory(path);
      final Stream stream = streams.remove(path);
      if (stream != null && stream.id != -1) {
        fileEventsApi.stopStream(stream.id);
      }
    }
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  public void unregister(final Path path) {
    internalExecutor.block(
        new Runnable() {
          @Override
          public void run() {
            unregisterImpl(path);
          }
        });
  }

  /** Closes the FileEventsApi and shuts down the {@code callbackExecutor}. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      super.close();
      internalExecutor.block(
          new Runnable() {
            @Override
            public void run() {
              streams.clear();
              fileEventsApi.close();
              callbackExecutor.close();
            }
          });
      internalExecutor.close();
    }
  }

  /** A no-op callback to invoke when streams are removed. */
  static class DefaultOnStreamRemoved implements Consumer<String> {
    DefaultOnStreamRemoved() {}

    @Override
    public void accept(String stream) {}
  }

  @SuppressWarnings("unchecked")
  private static Flags.Create fromOptionsOrDefault(final PathWatcher.Option... options) {
    final PathWatcher.Option option =
        ArrayOps.find(
            options,
            new Filter<PathWatcher.Option>() {
              @Override
              public boolean accept(PathWatcher.Option option) {
                return option instanceof FlagOption;
              }
            });
    return option == null
        ? new Flags.Create().setFileEvents().setNoDefer()
        : ((FlagOption) option).getFlags();
  }

  public ApplePathWatcher(
      final Consumer<PathWatcher.Event> onFileEvent,
      final Executor executor,
      final DirectoryRegistry directoryRegistry,
      final PathWatcher.Option... options)
      throws InterruptedException {
    this(
        0.01,
        fromOptionsOrDefault(options),
        Executor.make("com.swoval.files.ApplePathWatcher-callback-executor"),
        onFileEvent,
        DefaultOnStreamRemoved,
        executor,
        directoryRegistry);
  }
  /**
   * Creates a new ApplePathWatcher which is a wrapper around {@link FileEventsApi}, which in turn
   * is a native wrapper around <a
   * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1">
   * Apple File System Events</a>
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param callbackExecutor Executor to run callbacks on
   * @param onFileEvent {@link com.swoval.functional.Consumer} to run on file events
   * @param onStreamRemoved {@link com.swoval.functional.Consumer} to run when a redundant stream is
   *     removed from the underlying native file events implementation
   * @param executor The internal executor to manage the directory watcher state
   * @param directoryRegistry The registry of directories to monitor
   * @throws InterruptedException if the native file events implementation is interrupted during
   *     initialization
   */
  public ApplePathWatcher(
      final double latency,
      final Flags.Create flags,
      final Executor callbackExecutor,
      final Consumer<PathWatcher.Event> onFileEvent,
      final Consumer<String> onStreamRemoved,
      final Executor executor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    this.latency = latency;
    this.flags = flags;
    this.callbackExecutor = callbackExecutor;
    this.internalExecutor =
        executor == null
            ? Executor.make("com.swoval.files.ApplePathWatcher-internalExecutor")
            : executor;
    this.directoryRegistry = directoryRegistry;
    fileEventsApi =
        FileEventsApi.apply(
            new Consumer<FileEvent>() {
              @Override
              public void accept(final FileEvent fileEvent) {
                internalExecutor.run(
                    new Runnable() {
                      @Override
                      public void run() {
                        final String fileName = fileEvent.fileName;
                        final Path path = Paths.get(fileName);
                        if (directoryRegistry.accept(path)) {
                          PathWatcher.Event event;
                          if (fileEvent.mustScanSubDirs()) {
                            event = new PathWatcher.Event(path, Overflow);
                          } else if (fileEvent.itemIsFile()) {
                            if (fileEvent.isNewFile() && Files.exists(path)) {
                              event = new PathWatcher.Event(path, Create);
                            } else if (fileEvent.isRemoved() || !Files.exists(path)) {
                              event = new PathWatcher.Event(path, Delete);
                            } else {
                              event = new PathWatcher.Event(path, Modify);
                            }
                          } else if (Files.exists(path)) {
                            event = new PathWatcher.Event(path, Modify);
                          } else {
                            event = new PathWatcher.Event(path, Delete);
                          }
                          final PathWatcher.Event callbackEvent = event;
                          callbackExecutor.run(
                              new Runnable() {
                                @Override
                                public void run() {
                                  onFileEvent.accept(callbackEvent);
                                }
                              });
                        }
                      }
                    });
              }
            },
            new Consumer<String>() {
              @Override
              public void accept(final String stream) {
                internalExecutor.block(
                    new Runnable() {
                      @Override
                      public void run() {
                        new Runnable() {
                          @Override
                          public void run() {
                            streams.remove(Paths.get(stream));
                          }
                        }.run();
                      }
                    });
                callbackExecutor.run(
                    new Runnable() {
                      @Override
                      public void run() {
                        onStreamRemoved.accept(stream);
                      }
                    });
              }
            });
  }

  private Entry<Path, Stream> find(final Path path) {
    final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
    Entry<Path, Stream> result = null;
    while (result == null && it.hasNext()) {
      final Entry<Path, Stream> entry = it.next();
      if (path.startsWith(entry.getKey())) {
        result = entry;
      }
    }
    return result;
  }

  public static class FlagOption extends PathWatcher.Option {
    private final Flags.Create flags;

    public FlagOption(final Flags.Create flags) {
      super("FLAG_OPTION");
      this.flags = flags;
    }

    public Flags.Create getFlags() {
      return flags;
    }
  }

  public static class Options {
    public static FlagOption flags(final Flags.Create flags) {
      return new FlagOption(flags);
    }
  }
}
