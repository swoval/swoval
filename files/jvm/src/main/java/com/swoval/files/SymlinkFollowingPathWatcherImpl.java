package com.swoval.files;

import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.files.PathWatchers.FollowSymlinks;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.logging.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

class SymlinkFollowingPathWatcherImpl implements FollowSymlinks<Event> {
  private final SymlinkWatcher symlinkWatcher;
  private final PathWatcher<PathWatchers.Event> pathWatcher;
  private final Observers<PathWatchers.Event> observers;
  private final DirectoryRegistry pathWatcherDirectoryRegistry;

  SymlinkFollowingPathWatcherImpl(
      final PathWatcher<PathWatchers.Event> pathWatcher,
      final DirectoryRegistry directoryRegistry,
      final Logger logger)
      throws InterruptedException, IOException {
    this.pathWatcher = pathWatcher;
    this.pathWatcherDirectoryRegistry = directoryRegistry;
    this.observers = new Observers<>(logger);
    this.symlinkWatcher = new SymlinkWatcher(PathWatchers.noFollowSymlinks(logger), logger);
    pathWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {
            observers.onError(t);
          }

          @Override
          public void onNext(final Event event) {
            final TypedPath typedPath = event.getTypedPath();
            if (typedPath.exists() && typedPath.isSymbolicLink()) {
              try {
                final int maxDepth = directoryRegistry.maxDepthFor(typedPath.getPath());
                symlinkWatcher.addSymlink(typedPath.getPath(), maxDepth);
                if (typedPath.isDirectory()) {
                  handleNewDirectory(typedPath.getPath(), maxDepth, true);
                }
              } catch (final IOException e) {
                observers.onError(e);
              }
            } else if (!typedPath.exists()) {
              symlinkWatcher.remove(typedPath.getPath());
            }
            observers.onNext(event);
          }
        });
    symlinkWatcher.addObserver(observers);
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
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    final Either<IOException, Boolean> pathWatcherResult =
        pathWatcher.register(absolutePath, maxDepth);
    Either<IOException, Boolean> listResult = pathWatcherResult;
    if (pathWatcherResult.isRight()) {
      try {
        handleNewDirectory(absolutePath, maxDepth, false);
        listResult = Either.right(true);
      } catch (final IOException e) {
        listResult = Either.left(e);
      }
    }
    return listResult;
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void unregister(final Path path) {
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    try {
      final Iterator<TypedPath> it =
          FileTreeViews.list(
                  absolutePath,
                  pathWatcherDirectoryRegistry.maxDepthFor(absolutePath),
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
    pathWatcher.unregister(absolutePath);
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
