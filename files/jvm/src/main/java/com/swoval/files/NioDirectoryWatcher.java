package com.swoval.files;

import static com.swoval.files.Directory.Entry.DIRECTORY;
import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Overflow;
import static com.swoval.files.EntryFilters.AllPass;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.EntryFilter;
import com.swoval.files.Directory.Observer;
import com.swoval.functional.Consumer;
import com.swoval.functional.Filter;
import com.swoval.functional.IO;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Provides a DirectoryWatcher that is backed by a {@link java.nio.file.WatchService}. */
abstract class NioDirectoryWatcher extends DirectoryWatcher {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  protected final Executor callbackExecutor;
  protected final Executor executor;
  private final Map<Path, Directory<WatchedDirectory>> rootDirectories = new HashMap<>();
  private final boolean pollNewDirectories;
  private final DirectoryRegistry directoryRegistry;
  private final Converter<WatchedDirectory> converter;

  /**
   * Instantiate a NioDirectoryWatcher.
   *
   * @param register IO task to register path
   * @param callbackExecutor The Executor to invoke callbacks on
   * @param executor The Executor to internally manage the watcher
   * @param directoryRegistry Tracks the directories to watch
   * @param options The options for this watcher
   */
  NioDirectoryWatcher(
      final IO<Path, WatchedDirectory> register,
      final Executor callbackExecutor,
      final Executor executor,
      final DirectoryRegistry directoryRegistry,
      final DirectoryWatcher.Option... options) {
    this.directoryRegistry = directoryRegistry;
    this.pollNewDirectories =
        ArrayOps.contains(options, DirectoryWatcher.Options.POLL_NEW_DIRECTORIES);
    this.callbackExecutor = callbackExecutor;
    this.executor = executor;
    this.converter =
        new Converter<WatchedDirectory>() {
          @Override
          public WatchedDirectory apply(final Path path) {
            return register.apply(path).getOrElse(WatchedDirectories.INVALID);
          }
        };
  }

  private Directory<WatchedDirectory> getRoot(final Path root) {
    Directory<WatchedDirectory> result = rootDirectories.get(root);
    if (result == null) {
      try {
        result =
            new Directory<>(
                    root,
                    root,
                    converter,
                    Integer.MAX_VALUE,
                    new Filter<QuickFile>() {
                      @Override
                      public boolean accept(QuickFile quickFile) {
                        return directoryRegistry.accept(quickFile.toPath());
                      }
                    })
                .init();
        rootDirectories.put(root, result);
      } catch (final IOException e) {
      }
    }
    return result;
  }

  /**
   * Instantiate a NioDirectoryWatcher.
   *
   * @param register IO task to register path
   * @param callbackExecutor The Executor to invoke callbacks on
   * @param executor The Executor to internally manage the watcher
   * @param options The options for this watcher
   */
  NioDirectoryWatcher(
      final IO<Path, WatchedDirectory> register,
      final Executor callbackExecutor,
      final Executor executor,
      final DirectoryWatcher.Option... options) {
    this(register, callbackExecutor, executor, new DirectoryRegistry(), options);
  }

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  @Override
  public com.swoval.functional.Either<IOException, Boolean> register(
      final Path path, final int maxDepth) {
    return executor
        .block(
            new Callable<Boolean>() {
              @Override
              public Boolean call() throws IOException {
                return registerImpl(path, maxDepth);
              }
            })
        .castLeft(IOException.class);
  }
  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    executor.block(
        new Runnable() {
          @Override
          public void run() {
            directoryRegistry.removeDirectory(path);
            final Directory<WatchedDirectory> dir = getRoot(path.getRoot());
            if (dir != null) {
              List<Directory.Entry<WatchedDirectory>> entries = dir.list(true, AllPass);
              Collections.sort(entries);
              Collections.reverse(entries);
              final Iterator<Directory.Entry<WatchedDirectory>> it = entries.iterator();
              while (it.hasNext()) {
                final Directory.Entry<WatchedDirectory> watchedDirectory = it.next();
                if (!directoryRegistry.accept(watchedDirectory.path)) {
                  final Iterator<Directory.Entry<WatchedDirectory>> toCancel =
                      dir.remove(watchedDirectory.path).iterator();
                  while (toCancel.hasNext()) {
                    toCancel.next().getValue().close();
                  }
                }
              }
            }
          }
        });
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      super.close();
      executor.block(
          new Runnable() {
            @Override
            public void run() {
              callbackExecutor.close();
              final Iterator<Directory<WatchedDirectory>> it = rootDirectories.values().iterator();
              while (it.hasNext()) {
                final Directory<WatchedDirectory> dir = it.next();
                dir.entry().getValue().close();
                final Iterator<Directory.Entry<WatchedDirectory>> entries =
                    dir.list(true, AllPass).iterator();
                while (entries.hasNext()) {
                  entries.next().getValue().close();
                }
              }
            }
          });
      executor.close();
    }
  }

  private void maybeRunCallback(
      final Consumer<DirectoryWatcher.Event> callback, final DirectoryWatcher.Event event) {
    if (directoryRegistry.accept(event.path)) {
      callbackExecutor.run(
          new Runnable() {
            @Override
            public void run() {
              callback.accept(event);
            }
          });
    }
  }

  private void processPath(
      final Consumer<DirectoryWatcher.Event> callback,
      final Path path,
      final DirectoryWatcher.Event.Kind kind,
      HashSet<QuickFile> processedDirs,
      HashSet<Path> processedFiles) {
    final Set<QuickFile> newFiles = new HashSet<>();
    int maxDepth = directoryRegistry.maxDepthFor(path);
    add(path, maxDepth - (maxDepth == Integer.MAX_VALUE ? 0 : 1), newFiles);
    if (processedFiles.add(path)) {
      maybeRunCallback(callback, new DirectoryWatcher.Event(path, kind));
      final Iterator<QuickFile> it = newFiles.iterator();
      while (it.hasNext()) {
        final QuickFile file = it.next();
        if (file.isDirectory() && processedDirs.add(file)) {
          processPath(
              callback,
              file.toPath(),
              DirectoryWatcher.Event.Create,
              processedDirs,
              processedFiles);
        } else if (processedFiles.add(file.toPath())) {
          maybeRunCallback(
              callback, new DirectoryWatcher.Event(file.toPath(), DirectoryWatcher.Event.Create));
        }
      }
    }
  }

  protected void handleEvent(
      final Consumer<DirectoryWatcher.Event> callback,
      final Path path,
      final DirectoryWatcher.Event.Kind kind) {
    if (Files.isDirectory(path)) {
      processPath(callback, path, kind, new HashSet<QuickFile>(), new HashSet<Path>());
    } else {
      maybeRunCallback(callback, new DirectoryWatcher.Event(path, kind));
    }
  }

  protected void handleOverflow(final Consumer<DirectoryWatcher.Event> callback, final Path path) {
    final int maxDepth = directoryRegistry.maxDepthFor(path);
    boolean stop = false;
    while (!stop && maxDepth > 0) {
      try {
        boolean registered = false;
        final Set<QuickFile> files = new HashSet<>();
        final Iterator<Path> directoryIterator =
            directoryRegistry.registeredDirectories().iterator();
        while (directoryIterator.hasNext()) {
          files.add(new QuickFileImpl(directoryIterator.next().toString(), DIRECTORY));
        }
        maybePoll(path, files);
        final Iterator<QuickFile> it = files.iterator();
        while (it.hasNext()) {
          final QuickFile file = it.next();
          if (file.isDirectory()) {
            boolean regResult =
                registerImpl(
                    file.toPath(),
                    maxDepth == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxDepth - 1);
            registered = registered || regResult;
            if (regResult)
              callbackExecutor.run(
                  new Runnable() {
                    @Override
                    public void run() {
                      callback.accept(new DirectoryWatcher.Event(file.toPath(), Create));
                    }
                  });
          }
        }
        stop = !registered;
      } catch (NoSuchFileException e) {
        stop = false;
      } catch (IOException e) {
        stop = true;
      }
    }
    callbackExecutor.run(
        new Runnable() {
          @Override
          public void run() {
            callback.accept(new DirectoryWatcher.Event(path, Overflow));
          }
        });
  }

  private void maybePoll(final Path path, final Set<QuickFile> files) throws IOException {
    if (pollNewDirectories) {
      boolean result;
      do {
        result = false;
        final Iterator<QuickFile> it =
            QuickList.list(
                    path,
                    0,
                    false,
                    new Filter<QuickFile>() {
                      @Override
                      public boolean accept(QuickFile quickFile) {
                        return !quickFile.isDirectory()
                            || directoryRegistry.accept(quickFile.toPath());
                      }
                    })
                .iterator();
        while (it.hasNext()) {
          result = files.add(it.next()) || result;
        }
      } while (!Thread.currentThread().isInterrupted() && result);
    }
  }
  /**
   * Similar to register, but tracks all of the new files found in the directory. It polls the
   * directory until the contents stop changing to ensure that a callback is fired for each path in
   * the newly created directory (up to the maxDepth). The assumption is that once the callback is
   * fired for the path, it is safe to assume that no event for a new file in the directory is
   * missed. Without the polling, it would be possible that a new file was created in the directory
   * before we registered it with the watch service. If this happened, then no callback would be
   * invoked for that file.
   *
   * @param path The newly created directory to add
   * @param maxDepth The maximum depth of the subdirectory traversal
   * @param newFiles The set of files that are found for the newly created directory
   * @return true if no exception is thrown
   */
  private boolean add(final Path path, final int maxDepth, final Set<QuickFile> newFiles) {
    boolean result = true;
    try {
      if (directoryRegistry.maxDepthFor(path) < 0) {
        directoryRegistry.addDirectory(path, maxDepth);
      } else {
        final Directory<WatchedDirectory> dir = getRoot(path.getRoot());
        if (dir != null) {
          update(dir, path);
        }
      }
      maybePoll(path, newFiles);
    } catch (IOException e) {
      result = false;
    }
    return result;
  }

  private final Directory.Observer<WatchedDirectory> updateObserver =
      new Observer<WatchedDirectory>() {
        @Override
        public void onCreate(final Directory.Entry<WatchedDirectory> newEntry) {}

        @Override
        public void onDelete(final Directory.Entry<WatchedDirectory> oldEntry) {
          oldEntry.getValue().close();
        }

        @Override
        public void onUpdate(
            Directory.Entry<WatchedDirectory> oldEntry,
            Directory.Entry<WatchedDirectory> newEntry) {}

        @Override
        public void onError(Path path, IOException exception) {}
      };

  private void update(final Directory<WatchedDirectory> dir, final Path path) throws IOException {
    dir.update(path, DIRECTORY).observe(updateObserver);
  }

  private boolean registerImpl(final Path path, final int maxDepth) throws IOException {
    final int existingMaxDepth = directoryRegistry.maxDepthFor(path);
    boolean result = existingMaxDepth < maxDepth;
    Path realPath;
    try {
      realPath = path.toRealPath();
    } catch (IOException e) {
      realPath = path;
    }
    if (result) {
      directoryRegistry.addDirectory(path, maxDepth);
    } else if (!path.equals(realPath)) {
      /*
       * Note that watchedDir is not null, which means that this path has been
       * registered
       * with a different alias.
       */
      throw new FileSystemLoopException(path.toString());
    }
    if (result) {
      final Directory<WatchedDirectory> dir = getRoot(realPath.getRoot());
      if (dir != null) update(dir, path);
    }
    return result;
  }
}
