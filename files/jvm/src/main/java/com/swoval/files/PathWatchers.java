package com.swoval.files;

import com.swoval.functional.Consumer;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides factory methods to create instances of {@link com.swoval.files.PathWatcher}. It also
 * defines the {@link com.swoval.files.PathWatchers.Event} class for which the {@link
 * com.swoval.files.PathWatcher} will emit events.
 */
public class PathWatchers {

  static final Factory DEFAULT_FACTORY =
      new Factory() {
        @Override
        public PathWatcher create(
            Consumer<Event> callback,
            final Executor executor,
            final DirectoryRegistry directoryRegistry)
            throws InterruptedException, IOException {
          return get(callback, executor, directoryRegistry);
        }
      };

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param callback {@link com.swoval.functional.Consumer} to run on file events
   * @param options Runtime {@link Option} instances for the watcher. This is only relevant for the
   *     {@link NioPathWatcher} that is used on linux and windows.
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  public static PathWatcher get(final Consumer<Event> callback, final Option... options)
      throws IOException, InterruptedException {
    return get(
        callback,
        Executor.make("com.swoval.files.PathWatcher-internal-executor"),
        new DirectoryRegistry(),
        options);
  }

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param callback {@link Consumer} to run on file events
   * @param executor provides a single threaded context to manage state
   * @param options Runtime {@link Option} instances for the watcher. This is only relevant for the
   *     {@link NioPathWatcher} that is used on linux and windows.
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  static PathWatcher get(
      final Consumer<Event> callback, final Executor executor, final Option... options)
      throws IOException, InterruptedException {
    return get(callback, executor, new DirectoryRegistry(), options);
  }

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param callback {@link Consumer} to run on file events
   * @param executor provides a single threaded context to manage state
   * @param registry The registry of directories to monitor
   * @param options Runtime {@link Option} instances for the watcher. This is only relevant for the
   *     {@link NioPathWatcher} that is used on linux and windows.
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  static PathWatcher get(
      final Consumer<Event> callback,
      final Executor executor,
      final DirectoryRegistry registry,
      final Option... options)
      throws IOException, InterruptedException {
    return Platform.isMac()
        ? new ApplePathWatcher(callback, executor, registry, options)
        : PlatformWatcher.make(callback, executor, registry, options);
  }

  /**
   * Instantiates new {@link PathWatcher} instances with a {@link Consumer}. This is primarily so
   * that the {@link PathWatcher} in {@link FileCache} may be changed in testing.
   */
  public abstract static class Factory {

    /**
     * Creates a new PathWatcher
     *
     * @param callback The callback to invoke on directory updates
     * @param executor The executor on which internal updates are invoked
     * @return A PathWatcher instance
     * @throws InterruptedException if the PathWatcher is interrupted during initialization -- this
     *     can occur on mac
     * @throws IOException if an IOException occurs during initialization -- this can occur on linux
     *     and windows
     */
    public PathWatcher create(final Consumer<Event> callback, final Executor executor)
        throws InterruptedException, IOException {
      return create(callback, executor, new DirectoryRegistry());
    }

    /**
     * Creates a new PathWatcher
     *
     * @param callback The callback to invoke on directory updates
     * @param executor The executor on which internal updates are invoked
     * @param directoryRegistry The registry of directories to monitor
     * @return A PathWatcher instance
     * @throws InterruptedException if the PathWatcher is interrupted during initialization -- this
     *     can occur on mac
     * @throws IOException if an IOException occurs during initialization -- this can occur on linux
     *     and windows
     */
    public abstract PathWatcher create(
        final Consumer<Event> callback,
        final Executor executor,
        final DirectoryRegistry directoryRegistry)
        throws InterruptedException, IOException;
  }

  /** Options for the PathWatcher. */
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
     * Require that the PathWatcher poll newly created directories for files contained therein. A
     * creation event will be generated for any file found within the new directory. This is
     * somewhat expensive and may be redundant in some cases, see {@link FileCache} which does its
     * own polling for new directories.
     */
    public static final Option POLL_NEW_DIRECTORIES = new Option("POLL_NEW_DIRECTORIES");
  }

  /** Container for {@link PathWatcher} events */
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
}
