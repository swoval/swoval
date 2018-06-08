package com.swoval.files;

import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Delete;
import static com.swoval.files.DirectoryWatcher.Event.Modify;

import com.swoval.files.apple.FileEvent;
import com.swoval.files.apple.FileEventsApi;
import com.swoval.files.apple.FileEventsApi.Consumer;
import com.swoval.files.apple.Flags;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the DirectoryWatcher for Mac OSX using the <a
 * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html"
 * target="_blank">Apple File System Events Api</a>
 */
public class AppleDirectoryWatcher extends DirectoryWatcher {
  private final Map<Path, Stream> streams = new HashMap<>();
  private final Object lock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final double latency;
  private final Executor executor;
  private final Flags.Create flags;
  private final FileEventsApi fileEventsApi;
  private static final DefaultOnStreamRemoved DefaultOnStreamRemoved = new DefaultOnStreamRemoved();

  private static class Stream {
    public final int id;
    public final int maxDepth;
    private final int compDepth;

    Stream(final Path path, final int id, final int maxDepth) {
      this.id = id;
      this.maxDepth = maxDepth;
      compDepth = maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth + 1;
    }

    public boolean accept(final Path base, final Path child) {
      final int depth = base.relativize(child).getNameCount();
      return depth <= compDepth;
    }
  }

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return true if the path is a directory and has not previously been registered
   */
  @Override
  public boolean register(final Path path, final int maxDepth) {
    return register(path, flags, maxDepth);
  }

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags {@link com.swoval.files.apple.Flags.Create} to set for the directory
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return true if the path is a directory and has not previously been registered
   */
  public boolean register(final Path path, final Flags.Create flags, final int maxDepth) {
    boolean result = true;
    if (Files.isDirectory(path) && !path.equals(path.getRoot())) {
      final Entry<Path, Stream> entry = find(path);
      if (entry == null) {
        int id = fileEventsApi.createStream(path.toString(), latency, flags.getValue());
        if (id == -1) {
          result = false;
          System.err.println("Error watching " + path + ".");
        } else {
          synchronized (lock) {
            streams.put(path, new Stream(path, id, maxDepth));
          }
        }
      } else {
        final Path key = entry.getKey();
        final Stream stream = entry.getValue();
        final int depth = key.equals(path) ? 0 : key.relativize(path).getNameCount();
        final int newMaxDepth;
        if (maxDepth == Integer.MAX_VALUE || stream.maxDepth == Integer.MAX_VALUE) {
          newMaxDepth = Integer.MAX_VALUE;
        } else {
          int diff = maxDepth - stream.maxDepth + depth;
          newMaxDepth =
              diff > 0
                  ? (stream.maxDepth < Integer.MAX_VALUE - diff
                      ? stream.maxDepth + diff
                      : Integer.MAX_VALUE)
                  : stream.maxDepth;
        }
        if (newMaxDepth != stream.maxDepth) {
          streams.put(key, new Stream(path, stream.id, newMaxDepth));
        }
      }
    }
    return result;
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  public void unregister(Path path) {
    synchronized (lock) {
      if (!closed.get()) {
        final Stream stream = streams.remove(path);
        if (stream != null && stream.id != -1) {
          executor.run(
              new Runnable() {
                @Override
                public void run() {
                  fileEventsApi.stopStream(stream.id);
                }
              });
        }
      }
    }
  }

  /** Closes the FileEventsApi and shuts down the {@code executor}. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      super.close();
      synchronized (lock) {
        streams.clear();
      }
      fileEventsApi.close();
      executor.close();
    }
  }

  /**
   * Callback to run when the native file events api removes a redundant stream. This can occur when
   * a child directory is registered with the watcher before the parent.
   */
  public interface OnStreamRemoved {
    void apply(String stream);
  }

  static class DefaultOnStreamRemoved implements OnStreamRemoved {
    DefaultOnStreamRemoved() {}

    @Override
    public void apply(String stream) {}
  }

  public AppleDirectoryWatcher(
      final double latency, final Flags.Create flags, final DirectoryWatcher.Callback onFileEvent)
      throws InterruptedException {
    this(
        latency,
        flags,
        Executor.make("com.swoval.files.AppleDirectoryWatcher.executorThread"),
        onFileEvent,
        DefaultOnStreamRemoved);
  }

  public AppleDirectoryWatcher(
      final double latency,
      final Flags.Create flags,
      final Executor executor,
      final DirectoryWatcher.Callback onFileEvent)
      throws InterruptedException {
    this(latency, flags, executor, onFileEvent, DefaultOnStreamRemoved);
  }

  /**
   * Creates a new AppleDirectoryWatcher which is a wrapper around {@link FileEventsApi}, which in
   * turn is a native wrapper around <a
   * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1">
   * Apple File System Events</a>
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param executor Executor to run callbacks on
   * @param onFileEvent Callback to run on file events
   * @param onStreamRemoved Callback to run when a redundant stream is removed from the underlying
   *     native file events implementation
   * @throws InterruptedException if the native file events implementation is interrupted during
   *     initialization
   */
  public AppleDirectoryWatcher(
      final double latency,
      final Flags.Create flags,
      final Executor executor,
      final DirectoryWatcher.Callback onFileEvent,
      final OnStreamRemoved onStreamRemoved)
      throws InterruptedException {
    this.latency = latency;
    this.flags = flags;
    this.executor = executor;
    fileEventsApi =
        FileEventsApi.apply(
            new Consumer<FileEvent>() {
              @Override
              public void accept(final FileEvent fileEvent) {
                executor.run(
                    new Runnable() {
                      @Override
                      public void run() {
                        final String fileName = fileEvent.fileName;
                        final Path path = Paths.get(fileName);
                        final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
                        boolean validKey = false;
                        while (it.hasNext() && !validKey) {
                          final Entry<Path, Stream> entry = it.next();
                          final Path key = entry.getKey();
                          final Stream stream = entry.getValue();
                          validKey = path.equals(key) || stream.accept(key, path);
                        }
                        if (validKey) {
                          DirectoryWatcher.Event event;
                          if (fileEvent.itemIsFile()) {
                            if (fileEvent.isNewFile() && Files.exists(path)) {
                              event = new DirectoryWatcher.Event(path, Create);
                            } else if (fileEvent.isRemoved() || !Files.exists(path)) {
                              event = new DirectoryWatcher.Event(path, Delete);
                            } else {
                              event = new DirectoryWatcher.Event(path, Modify);
                            }
                          } else if (Files.exists(path)) {
                            event = new DirectoryWatcher.Event(path, Modify);
                          } else {
                            event = new DirectoryWatcher.Event(path, Delete);
                          }
                          onFileEvent.apply(event);
                        }
                      }
                    });
              }
            },
            new Consumer<String>() {
              @Override
              public void accept(final String stream) {
                executor.run(
                    new Runnable() {
                      @Override
                      public void run() {
                        synchronized (lock) {
                          streams.remove(stream);
                        }
                        onStreamRemoved.apply(stream);
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
}
