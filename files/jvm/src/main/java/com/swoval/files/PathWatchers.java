package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

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
   * Create a path watcher that periodically polls the file system to detect changes
   *
   * @param converter calculates the last modified time in milliseconds for the path watcher. This
   *     exists so that the converter can be replaced with a higher resolution calculation of the
   *     file system last modified time than is provided by the jvm, e.g.
   *     sbt.IO.getLastModifiedTimeOrZero.
   * @param followLinks toggles whether or not the targets of symbolic links should be monitored
   * @param pollInterval minimum duration between when polling ends and the next poll begins
   * @param timeUnit the time unit for which the pollInterval corresponds
   * @return the polling path watcher.
   * @throws InterruptedException if the polling thread cannot be started.
   */
  public static PathWatcher<PathWatchers.Event> polling(
      final Converter<Long> converter,
      final boolean followLinks,
      final long pollInterval,
      final TimeUnit timeUnit)
      throws InterruptedException {
    return new PollingPathWatcher(converter, followLinks, pollInterval, timeUnit);
  }

  /**
   * Create a path watcher that periodically polls the file system to detect changes
   *
   * @param followLinks toggles whether or not the targets of symbolic links should be monitored
   * @param pollInterval minimum duration between when polling ends and the next poll begins
   * @param timeUnit the time unit for which the pollInterval corresponds
   * @return the polling path watcher.
   * @throws InterruptedException if the polling thread cannot be started.
   */
  public static PathWatcher<PathWatchers.Event> polling(
      final boolean followLinks, final long pollInterval, final TimeUnit timeUnit)
      throws InterruptedException {
    return new PollingPathWatcher(followLinks, pollInterval, timeUnit);
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
  public static final class Event {
    private final TypedPath typedPath;
    private final Event.Kind kind;

    /**
     * Return the {@link TypedPath} associated with this Event.
     *
     * @return the {@link TypedPath}.
     */
    public TypedPath getTypedPath() {
      return typedPath;
    }

    /**
     * Returns the kind of event.
     *
     * @return the kind of event.
     */
    public Kind getKind() {
      return kind;
    }

    public Event(final TypedPath typedPath, final Event.Kind kind) {
      this.typedPath = typedPath;
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
      public static final Kind Create = new Kind("Create");
      /** The file was deleted. */
      public static final Kind Delete = new Kind("Delete");
      /** An error occurred processing the event. */
      public static final Kind Error = new Kind("Error");
      /** An existing file was modified. */
      public static final Kind Modify = new Kind("Modify");

      private final String name;

      Kind(final String name) {
        this.name = name;
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
