package com.swoval.files;

import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

class SymlinkFollowingPathWatcher implements PathWatcher<PathWatchers.Event> {
  private final SymlinkWatcher symlinkWatcher;
  private final PathWatcher<PathWatchers.Event> pathWatcher;
  private final Observers<PathWatchers.Event> observers = new Observers<>();
  private final DirectoryRegistry pathWatcherDirectoryRegistry;

  SymlinkFollowingPathWatcher(
      final PathWatcher<PathWatchers.Event> pathWatcher, final DirectoryRegistry directoryRegistry)
      throws InterruptedException, IOException {
    this.pathWatcher = pathWatcher;
    this.pathWatcherDirectoryRegistry = directoryRegistry;
    this.symlinkWatcher =
        new SymlinkWatcher(
            Platform.isMac()
                ? new ApplePathWatcher(new DirectoryRegistryImpl())
                : PlatformWatcher.make(false, new DirectoryRegistryImpl()));
    pathWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {
            observers.onError(t);
          }

          @Override
          public void onNext(final Event event) {
            if (event.exists() && event.isSymbolicLink()) {
              try {
                final int maxDepth = directoryRegistry.maxDepthFor(event.getPath());
                symlinkWatcher.addSymlink(event.getPath(), maxDepth);
                if (event.isDirectory()) {
                  handleNewDirectory(event.getPath(), maxDepth, true);
                }
              } catch (final IOException e) {
                observers.onError(e);
              }
            } else if (!event.exists()) {
              symlinkWatcher.remove(event.getPath());
            }
            observers.onNext(event);
          }
        });
    symlinkWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {
            observers.onError(t);
          }

          @Override
          public void onNext(final Event event) {
            observers.onNext(event);
          }
        });
  }

  private void handleNewDirectory(final Path path, final int maxDepth, final boolean trigger)
      throws IOException {
    final Iterator<TypedPath> it = FileTreeViews.list(path, maxDepth, AllPass).iterator();
    while (it.hasNext()) {
      final TypedPath tp = it.next();
      if (tp.isSymbolicLink()) {
        final Path p = tp.getPath();
        symlinkWatcher.addSymlink(p, pathWatcherDirectoryRegistry.maxDepthFor(p));
      }
      if (trigger) {
        observers.onNext(new Event(tp, Kind.Create));
      }
    }
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    final Either<IOException, Boolean> pathWatcherResult = pathWatcher.register(path, maxDepth);
    Either<IOException, Boolean> listResult = pathWatcherResult;
    if (pathWatcherResult.isRight()) {
      try {
        handleNewDirectory(path, maxDepth, false);
        listResult = Either.right(true);
      } catch (final IOException e) {
        listResult = Either.left(e);
      }
    }
    return listResult;
  }

  @Override
  public void unregister(final Path path) {
    try {
      final Iterator<TypedPath> it =
          FileTreeViews.list(
                  path,
                  pathWatcherDirectoryRegistry.maxDepthFor(path),
                  new Filter<TypedPath>() {
                    @Override
                    public boolean accept(final TypedPath typedPath) {
                      return typedPath.isSymbolicLink();
                    }
                  })
              .iterator();
      while (it.hasNext()) {
        symlinkWatcher.remove(it.next().getPath());
      }
    } catch (final IOException e) {
    }
    pathWatcher.unregister(path);
  }

  @Override
  public void close() {
    pathWatcher.close();
    symlinkWatcher.close();
  }

  @Override
  public int addObserver(final Observer<? super PathWatchers.Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(int handle) {
    observers.removeObserver(handle);
  }
}
