package com.swoval.files;

import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides factory methods to create instances of {@link com.swoval.files.PathWatcher}. It also
 * defines the {@link com.swoval.files.PathWatchers.Event} class for which the {@link
 * com.swoval.files.PathWatcher} will emit events.
 */
public class PathWatchers {
  private PathWatchers() {}
  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param followLinks toggles whether or not the targets of symbolic links should be monitored
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  public static PathWatcher<PathWatchers.Event> get(final boolean followLinks)
      throws IOException, InterruptedException {
    return get(followLinks, new DirectoryRegistryImpl());
  }

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param followLinks toggles whether or not the targets of symbolic links should be monitored
   * @param registry The registry of directories to monitor
   * @return PathWatcher for the runtime platform
   * @throws IOException when the underlying {@link java.nio.file.WatchService} cannot be
   *     initialized
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  static PathWatcher<Event> get(final boolean followLinks, final DirectoryRegistry registry)
      throws InterruptedException, IOException {
    return Platform.isMac()
        ? ApplePathWatchers.get(followLinks, registry)
        : PlatformWatcher.make(followLinks, registry);
  }

  /**
   * Create a PathWatcher for the runtime platform.
   *
   * @param registry The registry of directories to monitor
   * @return PathWatcher for the runtime platform
   * @throws InterruptedException when the {@link PathWatcher} is interrupted during initialization
   */
  static PathWatcher<Event> get(
      final boolean followLinks,
      final RegisterableWatchService service,
      final DirectoryRegistry registry)
      throws InterruptedException, IOException {
    return PlatformWatcher.make(followLinks, service, registry);
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

    /**
     * An enum like class to indicate the type of file event. It isn't an actual enum because the
     * scala.js codegen has problems with enum types.
     */
    public static class Kind {

      /** A new file was created. */
      public static final Kind Create = new Kind("Create", 1);
      /** The file was deleted. */
      public static final Kind Delete = new Kind("Delete", 2);
      /** An error occurred processing the event. */
      public static final Kind Error = new Kind("Error", 4);
      /** An existing file was modified. */
      public static final Kind Modify = new Kind("Modify", 3);

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
    }
  }
}
