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
  private final Map<String, Boolean> registered = new HashMap<>();
  private final Map<String, Integer> streams = new HashMap<>();
  private final Object lock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final double latency;
  private final Executor executor;
  private final Flags.Create flags;
  private final FileEventsApi fileEventsApi;
  private static final DefaultOnStreamRemoved DefaultOnStreamRemoved = new DefaultOnStreamRemoved();

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param recursive Toggles whether or not to monitor subdirectories
   * @return true if the path is a directory and has not previously been registered
   */
  @Override
  public boolean register(final Path path, final boolean recursive) {
    return register(path, flags, recursive);
  }

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags {@link com.swoval.files.apple.Flags.Create} to set for the directory
   * @param recursive Toggles whether the children of subdirectories should be monitored
   * @return true if the path is a directory and has not previously been registered
   */
  public boolean register(final Path path, final Flags.Create flags, final boolean recursive) {
    if (Files.isDirectory(path) && !path.equals(path.getRoot())) {
      if (!alreadyWatching(path)) {
        int id = fileEventsApi.createStream(path.toString(), latency, flags.getValue());
        if (id == -1) System.err.println("Error watching " + path + ".");
        else {
          synchronized (lock) {
            streams.put(path.toString(), id);
          }
        }
      }
      Boolean rec = registered.get(path.toString());
      if (rec == null || !rec) registered.put(path.toString(), recursive);
    }
    return true;
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
        final Integer id = streams.remove(path.toString());
        if (id != null && id != -1) {
          executor.run(new Runnable() {
                         @Override
                         public void run() {
                           fileEventsApi.stopStream(id);
                         }
                       });
        }
        registered.remove(path.toString());
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
                        final Iterator<Entry<String, Boolean>> it =
                            registered.entrySet().iterator();
                        boolean validKey = false;
                        while (it.hasNext() && !validKey) {
                          final Entry<String, Boolean> entry = it.next();
                          Path key = Paths.get(entry.getKey());
                          validKey =
                              path.equals(key)
                                  || (entry.getValue()
                                      ? path.startsWith(key)
                                      : path.getParent().equals(key));
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
                executor.run(new Runnable() {
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

  private boolean alreadyWatching(Path path) {
    return !path.equals(path.getRoot())
        && (streams.containsKey(path.toString()) || alreadyWatching(path.getParent()));
  }
}
