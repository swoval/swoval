package com.swoval.files;

import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Delete;
import static com.swoval.files.DirectoryWatcher.Event.Modify;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import com.swoval.concurrent.ThreadFactory;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NioDirectoryWatcher extends DirectoryWatcher {
  private final Object lock = new Object();
  private final Map<Path, WatchedDir> watchedDirs = new HashMap<>();
  private final WatchService watchService;
  private ExecutorService executor =
      Executors.newSingleThreadExecutor(
          new ThreadFactory(NioDirectoryWatcher.class.getName() + ".loop-thread"));

  public NioDirectoryWatcher(final Callback callback) throws IOException {
    watchService = FileSystems.getDefault().newWatchService();
    executor.submit(
        new Runnable() {
          private boolean stop = false;

          @Override
          public void run() {
            while (!stop) {
              try {
                final WatchKey key = watchService.take();
                final Path keyPath = (Path) key.watchable();
                final Iterator<WatchEvent<?>> it = key.pollEvents().iterator();
                while (it.hasNext()) {
                  final WatchEvent<?> e = it.next();
                  final Path path = ((Path) key.watchable()).resolve((Path) e.context());
                  final WatchEvent.Kind<?> k = e.kind();
                  final Event.Kind kind =
                      k == ENTRY_DELETE ? Delete : k == ENTRY_CREATE ? Create : Modify;
                  callback.apply(new DirectoryWatcher.Event(path, kind));
                  if (k == ENTRY_CREATE && Files.exists(path) && Files.isDirectory(path)) {
                    WatchedDir watchedDir = watchedDirs.get(keyPath);
                    if (watchedDir != null && watchedDir.recursive) {
                      register(path, true);
                      Files.walkFileTree(
                          path,
                          new FileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                final Path dir, final BasicFileAttributes attrs) {
                              if (path != dir) callback.apply(new DirectoryWatcher.Event(dir, Create));
                              return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(
                                final Path file, final BasicFileAttributes attrs) {
                              callback.apply(new DirectoryWatcher.Event(file, Create));
                              return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(
                                final Path file, final IOException exc) {
                              return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult postVisitDirectory(
                                final Path dir, final IOException exc) {
                              return FileVisitResult.CONTINUE;
                            }
                          });
                    }
                  }
                }
                if (!key.reset())
                  synchronized (lock) {
                    watchedDirs.remove(keyPath);
                  }
              } catch (ClosedWatchServiceException | InterruptedException e) {
                stop = true;
              } catch (Exception e) {
                System.out.println("Unexpected exception " + e);
                StackTraceElement[] elements = e.getStackTrace();
                int i = 0;
                while (i < elements.length) {
                  System.out.println(elements[i]);
                  i += 1;
                }
              }
            }
          }
        });
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    synchronized (lock) {
      final Iterator<WatchedDir> it = watchedDirs.values().iterator();
      while (it.hasNext()) {
        WatchKey key = it.next().key;
        key.cancel();
        key.reset();
      }
    }
    executor.shutdownNow();
    try {
      watchService.close();
    } catch (IOException e) {
    }
  }

  private class WatchedDir {
    final WatchKey key;
    final boolean recursive;

    WatchedDir(final WatchKey key, final boolean recursive) {
      this.key = key;
      this.recursive = recursive;
    }
  }

  private boolean registerImpl(final Path path, final boolean recursive) {
    synchronized (lock) {
      try {
        Path realPath = path.toRealPath();
        if (watchedDirs.containsKey(realPath)) {
          return false;
        } else {
          WatchKey key = realPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
          watchedDirs.put(realPath, new WatchedDir(key, recursive));
        }
      } catch (IOException e) {
        return false;
      }
      return !recursive || recursiveRegister(path);
    }
  }

  private boolean recursiveRegister(final Path path) {
    try {
      Files.walkFileTree(
          path,
          new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                final Path dir, final BasicFileAttributes attrs) {
              return (path.equals(dir)) || register(dir, true)
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      return false;
    }
    return true;
  }

  @Override
  public boolean register(final Path path, final boolean recursive) {
    return Files.exists(path) && registerImpl(path, recursive);
  }

  @Override
  public void unregister(final Path path) {
    final WatchedDir watchedDir = watchedDirs.remove(path);
    if (watchedDir != null) {
      WatchKey key = watchedDir.key;
      key.cancel();
      key.reset();
    }
  }
}
