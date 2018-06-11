package com.swoval.files;

import com.swoval.files.apple.AppleDirectoryWatcher;
import com.swoval.files.apple.Flags;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Watches directories for file changes. The api permits recursive watching of directories unlike
 * the <a href="https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html"
 * target="_blank"> java.nio.file.WatchService</a>. Some of the behavior may vary by platform due to
 * fundamental differences in the underlying file event apis. For example, Linux doesn't support
 * recursive directory monitoring via inotify, so it's possible in rare cases to miss file events
 * for newly created files in newly created directories. On OSX, it is difficult to disambiguate
 * file creation and modify events, so the {@link DirectoryWatcher.Event.Kind} is best effort, but
 * should not be relied upon to accurately reflect the state of the file.
 */
public abstract class DirectoryWatcher implements AutoCloseable {

  /** Callback to run when monitored files are created, updated and deleted. */
  public interface Callback {
    void apply(Event event);
  }

  /**
   * Register a path to monitor for file events. The watcher will only watch child subdirectories
   * up to maxDepth. For example, with a directory structure like /foo/bar/baz, if we register
   * the path /foo/ with maxDepth 0, we will be notified for any files that are created, updated or
   * deleted in foo, but not bar. If we increase maxDepth to 1, then the files in /foo/bar are
   * monitored, but not the files in /foo/bar/baz.
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return true if the registration is successful
   */
  public abstract boolean register(Path path, int maxDepth);
  /**
   * Register a path to monitor for file events. The monitoring may be recursive.
   *
   * @param path The directory to watch for file events
   * @param recursive Toggles whether or not to monitor subdirectories
   * @return true if the registration is successful
   */
  public boolean register(Path path, boolean recursive) {
    return register(path, recursive ? Integer.MAX_VALUE : 0);
  }

  /**
   * Register a path to monitor for file events recursively.
   *
   * @param path The directory to watch for file events
   * @return true if the registration is successful
   */
  public boolean register(Path path) {
    return register(path, true);
  }

  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  public abstract void unregister(Path path);

  /** Catch any exceptions in subclasses. */
  @Override
  public void close() {}

  /**
   * Create a DirectoryWatcher for the runtime platform.
   *
   * @param latency The latency used by the {@link AppleDirectoryWatcher} on osx
   * @param timeUnit The TimeUnit or the latency parameter
   * @param flags Flags used by the apple directory watcher
   * @param callback {@link Callback} to run on file events
   * @param executor provides a single threaded context to manage state
   * @return DirectoryWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link DirectoryWatcher} is interrupted during
   *     initialization
   */
  static DirectoryWatcher defaultWatcher(
      final long latency,
      final TimeUnit timeUnit,
      final Flags.Create flags,
      final Callback callback,
      final Executor executor)
      throws IOException, InterruptedException {
    return Platform.isMac()
        ? new AppleDirectoryWatcher(timeUnit.toNanos(latency) / 1.0e9, flags, callback, executor)
        : new NioDirectoryWatcher(callback, executor);
  }

  /**
   * Create a platform compatible DirectoryWatcher.
   *
   * @param callback {@link Callback} to run on file events
   * @param executor The executor to run internal callbacks on
   * @return DirectoryWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link DirectoryWatcher} is interrupted during
   *     initialization
   */
  public static DirectoryWatcher defaultWatcher(final Callback callback, final Executor executor)
      throws IOException, InterruptedException {
    return defaultWatcher(
        10,
        TimeUnit.MILLISECONDS,
        new Flags.Create().setNoDefer().setFileEvents(),
        callback,
        executor);
  }

  /**
   * Instantiates new {@link DirectoryWatcher} instances with a {@link Callback}. This is primarily
   * so that the {@link DirectoryWatcher} in {@link FileCache} may be changed in testing.
   */
  public interface Factory {

    /**
     * Creates a new DirectoryWatcher
     *
     * @param callback The callback to invoke on directory updates
     * @param executor The executor on which internal updates are invoked
     * @return A DirectoryWatcher instance
     * @throws InterruptedException if the DirectoryWatcher is interrupted during initialization -- this can occur on mac
     * @throws IOException if an IOException occurs during initialization -- this can occur on linux and windows
     */
    DirectoryWatcher create(final Callback callback, final Executor executor)
        throws InterruptedException, IOException;
  }

  public static final Factory DEFAULT_FACTORY =
      new Factory() {
        @Override
        public DirectoryWatcher create(Callback callback, final Executor executor)
            throws InterruptedException, IOException {
          return defaultWatcher(callback, executor);
        }
      };

  /** Container for {@link DirectoryWatcher} events */
  public static final class Event {
    public final Path path;
    public final Event.Kind kind;
    public static final Kind Create = new Kind("Create");
    public static final Kind Delete = new Kind("Delete");
    public static final Kind Modify = new Kind("Modify");
    public static final Kind Overflow = new Kind("Overflow");

    public Event(final Path path, final Event.Kind kind) {
      this.path = path;
      this.kind = kind;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Event) {
        Event that = (Event) other;
        return this.path.equals(that.path) && this.kind.equals(that.kind);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return path.hashCode() ^ kind.hashCode();
    }

    @Override
    public String toString() {
      return "Event(" + path + ", " + kind + ")";
    }

    /**
     * An enum like class to indicate the type of file event. It isn't an actual enum because the
     * scala.js codegen has problems with enum types.
     */
    public static class Kind {
      private final String name;

      Kind(String name) {
        this.name = name;
      }

      @Override
      public String toString() {
        return name;
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Kind && ((Kind) other).name.equals(this.name);
      }

      @Override
      public int hashCode() {
        return name.hashCode();
      }
    }
  }
}
