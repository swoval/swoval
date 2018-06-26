package com.swoval.files;

import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;

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

  /**
   * Register a path to monitor for file events. The watcher will only watch child subdirectories up
   * to maxDepth. For example, with a directory structure like /foo/bar/baz, if we register the path
   * /foo/ with maxDepth 0, we will be notified for any files that are created, updated or deleted
   * in foo, but not bar. If we increase maxDepth to 1, then the files in /foo/bar are monitored,
   * but not the files in /foo/bar/baz.
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  public abstract Either<IOException, Boolean> register(Path path, int maxDepth);
  /**
   * Register a path to monitor for file events. The monitoring may be recursive.
   *
   * @param path The directory to watch for file events
   * @param recursive Toggles whether or not to monitor subdirectories
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  public Either<IOException, Boolean> register(Path path, boolean recursive) {
    return register(path, recursive ? Integer.MAX_VALUE : 0);
  }

  /**
   * Register a path to monitor for file events recursively.
   *
   * @param path The directory to watch for file events
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  public Either<IOException, Boolean> register(Path path) {
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
   * @param callback {@link com.swoval.functional.Consumer} to run on file events
   * @param executor provides a single threaded context to manage state
   * @param options Runtime {@link DirectoryWatcher.Option} instances for the watcher. This is only
   *     relevant for the {@link NioDirectoryWatcher} that is used on linux and windows.
   * @return DirectoryWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link DirectoryWatcher} is interrupted during
   *     initialization
   */
  static DirectoryWatcher defaultWatcher(
      final Consumer<DirectoryWatcher.Event> callback,
      final Executor executor,
      final Option... options)
      throws IOException, InterruptedException {
    return Platform.isMac()
        ? new AppleDirectoryWatcher(callback, executor, options)
        : PlatformWatcher.make(callback, executor, options);
  }

  /**
   * Instantiates new {@link DirectoryWatcher} instances with a {@link
   * com.swoval.functional.Consumer}. This is primarily so that the {@link DirectoryWatcher} in
   * {@link FileCache} may be changed in testing.
   */
  public interface Factory {

    /**
     * Creates a new DirectoryWatcher
     *
     * @param callback The callback to invoke on directory updates
     * @param executor The executor on which internal updates are invoked
     * @return A DirectoryWatcher instance
     * @throws InterruptedException if the DirectoryWatcher is interrupted during initialization --
     *     this can occur on mac
     * @throws IOException if an IOException occurs during initialization -- this can occur on linux
     *     and windows
     */
    DirectoryWatcher create(
        final Consumer<DirectoryWatcher.Event> callback, final Executor executor)
        throws InterruptedException, IOException;
  }

  public static final Factory DEFAULT_FACTORY =
      new Factory() {
        @Override
        public DirectoryWatcher create(
            Consumer<DirectoryWatcher.Event> callback, final Executor executor)
            throws InterruptedException, IOException {
          return defaultWatcher(callback, executor);
        }
      };

  /** Container for {@link DirectoryWatcher} events */
  public static final class Event {
    public final Path path;
    public final Event.Kind kind;
    public static final Kind Create = new Kind("Create", 1);
    public static final Kind Delete = new Kind("Delete", 2);
    public static final Kind Error = new Kind("Error", 4);
    public static final Kind Modify = new Kind("Modify", 3);
    public static final Kind Overflow = new Kind("Overflow", 0);

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
    public static class Kind implements Comparable<Kind> {
      private final String name;
      private final int priority;

      Kind(final String name, final int priority) {
        this.name = name;
        this.priority = priority;
      }

      @Override
      public String toString() {
        return name;
      }

      @Override
      public boolean equals(final Object other) {
        return other instanceof Kind && ((Kind) other).name.equals(this.name);
      }

      @Override
      public int hashCode() {
        return name.hashCode();
      }

      @Override
      public int compareTo(final Kind that) {
        return Integer.compare(this.priority, that.priority);
      }
    }
  }

  /** Options for the DirectoryWatcher. */
  public static class Option {
    private final String name;

    public Option(final String name) {
      this.name = name;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof Option && ((Option) obj).name.equals(this.name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** Container class for static options. */
  public static final class Options {
    /**
     * Require that the DirectoryWatcher poll newly created directories for files contained therein.
     * A creation event will be generated for any file found within the new directory. This is
     * somewhat expensive and may be redundant in some cases, see {@link FileCache} which does its
     * own polling for new directories.
     */
    public static final Option POLL_NEW_DIRECTORIES = new Option("POLL_NEW_DIRECTORIES");
  }
}
