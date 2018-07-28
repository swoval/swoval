package com.swoval.files;

import com.swoval.files.Executor.Thread;
import com.swoval.files.FileTreeDataViews.OnError;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * This is a {@link PathWatcher} that follows symlinks and monitors the target of the links for
 * changes.
 */
public interface SymlinkFollowingPathWatcher extends PathWatcher<PathWatchers.Event> {}

class SymlinkFollowingPathWatcherImpl implements SymlinkFollowingPathWatcher {
  private final SymlinkWatcher symlinkWatcher;
  private final PathWatcher<PathWatchers.Event> pathWatcher;
  private final Observers<PathWatchers.Event> observers = new Observers<>();
  private final DirectoryRegistry pathWatcherDirectoryRegistry;
  private final Executor internalExecutor;

  SymlinkFollowingPathWatcherImpl(
      final PathWatcher<PathWatchers.Event> pathWatcher,
      final Observer<PathWatchers.Event> observer,
      final Executor internalExecutor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException, IOException {
    this.pathWatcher = pathWatcher;
    this.pathWatcherDirectoryRegistry = directoryRegistry;
    this.internalExecutor = internalExecutor;
    this.symlinkWatcher =
        new SymlinkWatcher(
            PathWatchers.get(internalExecutor, new DirectoryRegistryImpl()),
            new OnError() {
              @Override
              public void apply(final IOException exception) {}
            },
            internalExecutor);
    pathWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {}

          @Override
          public void onNext(final Event event) {
            if (event.exists() && event.isSymbolicLink()) {
              symlinkWatcher.addSymlink(
                  event.getPath(), directoryRegistry.maxDepthFor(event.getPath()));
            } else if (!event.exists()) {
              symlinkWatcher.remove(event.getPath());
            }
          }
        });
    symlinkWatcher.addObserver(observer);
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    return internalExecutor
        .block(
            new Function<Thread, Boolean>() {
              @Override
              public Boolean apply(final Thread thread) throws IOException {
                final Either<IOException, Boolean> pathWatcherResult =
                    pathWatcher.register(path, maxDepth);
                if (pathWatcherResult.isRight()) {
                  final Iterator<TypedPath> it =
                      FileTreeViews.list(
                              path,
                              maxDepth,
                              new Filter<TypedPath>() {
                                @Override
                                public boolean accept(final TypedPath typedPath) {
                                  return typedPath.isSymbolicLink();
                                }
                              })
                          .iterator();
                  while (it.hasNext()) {
                    final Path path = it.next().getPath();
                    symlinkWatcher.addSymlink(path, pathWatcherDirectoryRegistry.maxDepthFor(path));
                  }
                  return true;
                } else {
                  throw Either.leftProjection(pathWatcherResult).getValue();
                }
              }
            })
        .castLeft(IOException.class, false);
  }

  @Override
  public void unregister(final Path path) {
    internalExecutor.block(
        new Consumer<Thread>() {
          @Override
          public void accept(Thread thread) {

            pathWatcher.unregister(path);
          }
        });
  }

  @Override
  public void close() {
    internalExecutor.block(
        new Consumer<Thread>() {
          @Override
          public void accept(Thread thread) {
            pathWatcher.close();
            symlinkWatcher.close();
          }
        });
  }

  @Override
  public int addObserver(final Observer<PathWatchers.Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(int handle) {
    observers.removeObserver(handle);
  }
}
