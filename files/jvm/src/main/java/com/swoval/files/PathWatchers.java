package com.swoval.files;

import com.swoval.files.Executor.Thread;
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
  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param callback {@link com.swoval.functional.Consumer} to run on file events
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  public static PathWatcher<PathWatchers.Event> get(final Consumer<Event> callback)
      throws IOException, InterruptedException {
    return get(
        new BiConsumer<Event, Thread>() {
          @Override
          public void accept(final Event event, final Thread thread) {
            callback.accept(event);
          }
        },
        Executor.make("com.swoval.files.PathWatcher-internal-executor"),
        new DirectoryRegistryImpl());
  }

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param callback {@link Consumer} to run on file events
   * @param executor provides a single threaded context to manage state
   * @param registry The registry of directories to monitor
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  static ManagedPathWatcher get(
      final BiConsumer<Event, Executor.Thread> callback,
      final Executor executor,
      final DirectoryRegistry registry)
      throws IOException, InterruptedException {
    return Platform.isMac()
        ? new ApplePathWatcher(executor.delegate(callback), executor, registry)
        : PlatformWatcher.make(callback, executor, registry);
  }

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param callback {@link Consumer} to run on file events
   * @param executor provides a single threaded context to manage state
   * @param registry The registry of directories to monitor
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  static ManagedPathWatcher get(
      final BiConsumer<Event, Executor.Thread> callback,
      final RegisterableWatchService service,
      final Executor executor,
      final DirectoryRegistry registry)
      throws InterruptedException {
    return PlatformWatcher.make(callback, service, executor, registry);
  }

  static final class Overflow {
    private final Path path;

    Overflow(final Path path) {
      this.path = path;
    }

    public Path getPath() {
      return path;
    }
  }
  /** Container for {@link PathWatcher} events. */
  public static final class Event implements TypedPath {
    private final TypedPath typedPath;
    private final Event.Kind kind;

    /**
     * Returns the path that triggered the event.
     *
     * @return the path that triggered the event.
     */
    public Path getPath() {
      return typedPath.getPath();
    }

    @Override
    public boolean exists() {
      return typedPath.exists();
    }

    @Override
    public boolean isDirectory() {
      return typedPath.isDirectory();
    }

    @Override
    public boolean isFile() {
      return typedPath.isFile();
    }

    @Override
    public boolean isSymbolicLink() {
      return typedPath.isSymbolicLink();
    }

    @Override
    public Path toRealPath() {
      return typedPath.toRealPath();
    }

    /**
     * Returns the kind of event.
     *
     * @return the kind of event.
     */
    public Kind getKind() {
      return kind;
    }

    public Event(final TypedPath path, final Event.Kind kind) {
      this.typedPath = path;
      this.kind = kind;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Event) {
        Event that = (Event) other;
        return this.typedPath.equals(that.typedPath) && this.kind.equals(that.kind);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return typedPath.hashCode() ^ kind.hashCode();
    }

    @Override
    public String toString() {
      return "Event(" + typedPath.getPath() + ", " + kind + ")";
    }

    @Override
    public int compareTo(TypedPath that) {
      return this.getPath().compareTo(that.getPath());
    }

    /**
     * An enum like class to indicate the type of file event. It isn't an actual enum because the
     * scala.js codegen has problems with enum types.
     */
    public static class Kind implements Comparable<Kind> {

      /** A new file was created. */
      public static final Kind Create = new Kind("Create", 1);
      /** The file was deleted. */
      public static final Kind Delete = new Kind("Delete", 2);
      /** An error occurred processing the event. */
      public static final Kind Error = new Kind("Error", 4);
      /** An existing file was modified. */
      public static final Kind Modify = new Kind("Modify", 3);
      /** This path might have changed. */
      public static final Kind Refresh = new Kind("Refresh", 4);

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

  interface Factory {
    ManagedPathWatcher create(
        final BiConsumer<Event, Executor.Thread> consumer,
        final Executor executor,
        final DirectoryRegistry registry);
  }
}
