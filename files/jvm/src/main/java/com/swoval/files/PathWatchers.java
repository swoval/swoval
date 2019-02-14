package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.functional.Either;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// Ignore the errors in javadoc in intellij. It is getting confused by having the java and
// js implementations.
/**
 * Provides factory methods to create instances of {@link com.swoval.files.PathWatcher}. It also
 * defines the {@link com.swoval.files.PathWatchers.Event} class for which the {@link
 * com.swoval.files.PathWatcher} will emit events.
 */
public class PathWatchers {
  private PathWatchers() {}

  /**
   * Create a PathWatcher that will not follow symlinks. The implementation will be platform
   * dependent.
   *
   * @param converter function to convert a {@link TypedPath} to the generic data type `T`.
   * @param logger the logger for t
   * @param <T> the generic type of events created by this path watcher
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static <T> NoFollowSymlinks<T> noFollowSymlinks(
      final Converter<T> converter, final Logger logger) throws IOException, InterruptedException {
    return new NoFollowWrapper<>(
        PathWatchers.<T>get(converter, new DirectoryRegistryImpl(), logger));
  }

  /**
   * Create a PathWatcher that will not follow symlinks. The implementation will be platform
   * dependent.
   *
   * @param converter function to convert a {@link TypedPath} to the generic data type `T`.
   * @param <T> the generic type of events created by this path watcher
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static <T> NoFollowSymlinks<T> noFollowSymlinks(final Converter<T> converter)
      throws IOException, InterruptedException {
    return noFollowSymlinks(converter, Loggers.getLogger());
  }

  /**
   * Create a PathWatcher that will not follow symlinks and generates events of type {@link Event}.
   * The implementation will be platform dependent.
   *
   * @param logger the logger to use
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static NoFollowSymlinks<Event> noFollowSymlinks(final Logger logger)
      throws IOException, InterruptedException {
    return new NoFollowWrapper<>(get(new DirectoryRegistryImpl(), logger));
  }

  /**
   * Create a PathWatcher that will not follow symlinks and generates events of type {@link Event}.
   * The implementation will be platform dependent.
   *
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static NoFollowSymlinks<Event> noFollowSymlinks()
      throws IOException, InterruptedException {
    return noFollowSymlinks(Loggers.getLogger());
  }

  /**
   * Create a PathWatcher that will follow symlinks and generate file events for the symlink when
   * its target is modifified. The implementation will be platform dependent.
   *
   * @param converter function to convert a {@link TypedPath} to the generic data type `T`.
   * @param logger the logger for t
   * @param <T> the generic type of events created by this path watcher
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static <T> FollowSymlinks<T> followSymlinks(
      final Converter<T> converter, final Logger logger) throws IOException, InterruptedException {
    return new FollowWrapper<>(new ConvertedPathWatcher<>(follow(logger), converter, logger));
  }

  /**
   * Create a PathWatcher that will follow symlinks and generate file events for the symlink when
   * its target is modifified. The implementation will be platform dependent.
   *
   * @param converter function to convert a {@link TypedPath} to the generic data type `T`.
   * @param <T> the generic type of events created by this path watcher
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static <T> FollowSymlinks<T> followSymlinks(final Converter<T> converter)
      throws IOException, InterruptedException {
    return followSymlinks(converter, Loggers.getLogger());
  }

  /**
   * Create a PathWatcher that will not follow symlinks and generates events of type {@link Event}.
   * The implementation will be platform dependent.
   *
   * @param logger the logger
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static FollowSymlinks<Event> followSymlinks(final Logger logger)
      throws IOException, InterruptedException {
    return new FollowWrapper<>(follow(logger));
  }

  /**
   * Create a PathWatcher that will not follow symlinks and generates events of type {@link Event}.
   * The implementation will be platform dependent.
   *
   * @return a PathWatcher that does not follow symlinks.
   * @throws IOException when the platform specific monitoring service cannot be initialized due to
   *     an io error
   * @throws InterruptedException when the platform specific monitoring service cannot initialize
   *     its background threads
   */
  public static FollowSymlinks<Event> followSymlinks() throws IOException, InterruptedException {
    return new FollowWrapper<>(follow(Loggers.getLogger()));
  }

  private static PathWatcher<Event> follow(final Logger logger)
      throws InterruptedException, IOException {
    final DirectoryRegistry directoryRegistry = new DirectoryRegistryImpl();
    final PathWatcher<Event> pathWatcher = get(directoryRegistry, logger);
    return new SymlinkFollowingPathWatcherImpl(pathWatcher, directoryRegistry, logger);
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
      final boolean followLinks,
      final long pollInterval,
      final TimeUnit timeUnit,
      final Logger logger)
      throws InterruptedException {
    return new PollingPathWatcher(followLinks, pollInterval, timeUnit, logger);
  }

  private static <T> PathWatcher<T> get(
      final Converter<T> converter, final DirectoryRegistry registry, final Logger logger)
      throws InterruptedException, IOException {
    return new ConvertedPathWatcher<T>(get(registry, logger), converter, logger);
  }

  private static PathWatcher<Event> get(final DirectoryRegistry registry, final Logger logger)
      throws InterruptedException, IOException {
    return Platform.isMac()
        ? ApplePathWatchers.get(registry, logger)
        : PlatformWatcher.make(registry, logger);
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
      /** The watching service overflowed so it may be necessary to poll. */
      public static final Kind Overflow = new Kind("Overflow");

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

  private static class ConvertedPathWatcher<T> implements PathWatcher<T> {
    private final PathWatcher<Event> pathWatcher;
    private final Observers<T> observers;
    private final Converter<T> converter;
    private final int handle;

    ConvertedPathWatcher(
        final PathWatcher<Event> pathWatcher, final Converter<T> converter, final Logger logger) {
      this.pathWatcher = pathWatcher;
      this.converter = converter;
      this.observers = new Observers<>(logger);
      this.handle =
          pathWatcher.addObserver(
              new Observer<Event>() {
                @Override
                public void onError(final Throwable t) {
                  observers.onError(t);
                }

                @Override
                public void onNext(Event event) {
                  observe(event);
                }
              });
    }

    @Override
    public Either<IOException, Boolean> register(Path path, int maxDepth) {
      return pathWatcher.register(path, maxDepth);
    }

    @Override
    public void unregister(Path path) {
      pathWatcher.unregister(path);
    }

    @Override
    public void close() {
      pathWatcher.removeObserver(this.handle);
      observers.close();
      pathWatcher.close();
    }

    public int addObserver(Observer<? super T> observer) {
      return observers.addObserver(observer);
    }

    @Override
    public void removeObserver(int handle) {
      observers.removeObserver(handle);
    }

    private void observe(final Event event) {
      try {
        observers.onNext(converter.apply(event.getTypedPath()));
      } catch (final IOException e) {
        observers.onError(e);
      }
    }
  }

  private static class Wrapper<T> implements PathWatcher<T> {
    private final PathWatcher<T> delegate;

    Wrapper(final PathWatcher<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
      return delegate.register(path, maxDepth);
    }

    @Override
    public void unregister(final Path path) {
      delegate.unregister(path);
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public int addObserver(final Observer<? super T> observer) {
      return delegate.addObserver(observer);
    }

    @Override
    public void removeObserver(final int handle) {
      delegate.removeObserver(handle);
    }
  }

  private static final class NoFollowWrapper<T> extends Wrapper<T> implements NoFollowSymlinks<T> {
    NoFollowWrapper(final PathWatcher<T> delegate) {
      super(delegate);
    }

    @Override
    public String toString() {
      return "NoFollowSymlinksPathWatcher@" + System.identityHashCode(this);
    }
  }

  private static final class FollowWrapper<T> extends Wrapper<T> implements FollowSymlinks<T> {
    FollowWrapper(final PathWatcher<T> delegate) {
      super(delegate);
    }

    @Override
    public String toString() {
      return "SymlinkFollowingPathWatcher@" + System.identityHashCode(this);
    }
  }

  public interface FollowSymlinks<T> extends PathWatcher<T> {}

  public interface NoFollowSymlinks<T> extends PathWatcher<T> {}
}
