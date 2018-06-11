package com.swoval.files;

import static java.util.Map.Entry;

import com.swoval.concurrent.Lock;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors symlink targets. The {@link SymlinkWatcher} maintains a mapping of symlink targets to
 * symlink. When the symlink target is modified, the watcher will detect the update and invoke a
 * provided {@link SymlinkWatcher.EventConsumer} for the symlink.
 */
class SymlinkWatcher implements AutoCloseable {
  private final Lock lock = new Lock();
  private final Map<Path, RegisteredPath> watchedSymlinksByDirectory = new HashMap<>();
  private final Map<Path, RegisteredPath> watchedSymlinksByTarget = new HashMap<>();
  private final OnLoop onLoop;

  interface EventConsumer {
    void accept(final Path path);
  }

  private static final class RegisteredPath {
    public final Path path;
    public final Set<Path> paths = new HashSet<>();
    public final boolean isDirectory;

    RegisteredPath(final Path path, final boolean isDirectory, final Path base) {
      this.path = path;
      this.isDirectory = isDirectory;
      paths.add(base);
    }
  }

  private RegisteredPath find(final Path path, final Map<Path, RegisteredPath> map) {
    final RegisteredPath result = map.get(path);
    if (result != null) return result;
    else if (path == null || path.getNameCount() == 0) return null;
    else {
      final Path parent = path.getParent();
      if (parent == null || parent.getNameCount() == 0) return null;
      else return find(parent, map);
    }
  }

  SymlinkWatcher(
      final EventConsumer handleEvent, final DirectoryWatcher.Factory factory, final OnLoop onLoop)
      throws IOException, InterruptedException {
    this.onLoop = onLoop;
    final DirectoryWatcher.Callback callback =
        new DirectoryWatcher.Callback() {
          @SuppressWarnings("unchecked")
          @Override
          public void apply(final DirectoryWatcher.Event event) {
            if (lock.lock()) {
              final List<Runnable> callbacks = new ArrayList<>();
              try {
                final Path path = event.path;
                {
                  final RegisteredPath registeredPath = find(path, watchedSymlinksByTarget);
                  if (registeredPath != null) {
                    final Path relativized = registeredPath.path.relativize(path);
                    final Iterator<Path> it = registeredPath.paths.iterator();
                    while (it.hasNext()) {
                      final Path p = it.next();
                      callbacks.add(
                          new Runnable() {
                            @Override
                            public void run() {
                              if (registeredPath.isDirectory)
                                handleEvent.accept(p.resolve(relativized));
                              else handleEvent.accept(p);
                            }
                          });
                    }
                  }
                }
                if (!Files.exists(event.path)) {
                  final Path parent = event.path.getParent();
                  watchedSymlinksByTarget.remove(event.path);
                  final RegisteredPath registeredPath = watchedSymlinksByDirectory.get(parent);
                  if (registeredPath != null) {
                    registeredPath.paths.remove(event.path);
                    if (registeredPath.paths.isEmpty()) {
                      watcher.unregister(parent);
                      watchedSymlinksByDirectory.remove(parent);
                    }
                  }
                }
              } finally {
                lock.unlock();
              }
              final Iterator<Runnable> it = callbacks.iterator();
              while (it.hasNext()) {
                it.next().run();
              }
            }
          }
        };
    this.watcher = factory.create(callback);
  }

  /*
   * This declaration must go below the constructor for javascript codegen.
   */
  private final DirectoryWatcher watcher;

  @Override
  public void close() {
    watcher.close();
  }

  /**
   * Start monitoring a symlink. As long as the target exists, this method will check if the parent
   * directory of the target is being monitored. If the parent isn't being registered, we register
   * it with the watch service. We add the target symlink to the set of symlinks watched in the
   * parent directory. We also add the base symlink to the set of watched symlinks for this
   * particular target.
   *
   * @param path The symlink base file.
   */
  @SuppressWarnings("EmptyCatchBlock")
  void addSymlink(final Path path, final boolean isDirectory) {
    if (lock.lock()) {
      try {
        final Path realPath = path.toRealPath();
        final RegisteredPath targetRegistrationPath = watchedSymlinksByTarget.get(realPath);
        if (targetRegistrationPath == null) {
          final Path registrationPath = isDirectory ? realPath : realPath.getParent();
          final RegisteredPath registeredPath = watchedSymlinksByDirectory.get(registrationPath);
          if (registeredPath != null) {
            if (!isDirectory || registeredPath.isDirectory) {
              registeredPath.paths.add(realPath);
              final RegisteredPath symlinkChildren = watchedSymlinksByTarget.get(realPath);
              if (symlinkChildren != null) {
                symlinkChildren.paths.add(path);
              }
            } else if (watcher.register(registrationPath, true)) {
              final RegisteredPath parentPath =
                  new RegisteredPath(registrationPath, true, realPath);
              parentPath.paths.addAll(registeredPath.paths);
              watchedSymlinksByDirectory.put(registrationPath, parentPath);
              final RegisteredPath symlinkPaths = new RegisteredPath(realPath, true, path);
              final RegisteredPath existingSymlinkPaths = watchedSymlinksByTarget.get(realPath);
              if (existingSymlinkPaths != null) {
                symlinkPaths.paths.addAll(existingSymlinkPaths.paths);
              }
              watchedSymlinksByTarget.put(realPath, symlinkPaths);
            }
          } else {
            if (watcher.register(registrationPath, isDirectory)) {
              watchedSymlinksByDirectory.put(
                  registrationPath, new RegisteredPath(registrationPath, isDirectory, realPath));
              watchedSymlinksByTarget.put(
                  realPath, new RegisteredPath(realPath, isDirectory, path));
            }
          }
        } else if (Files.isDirectory(realPath)) {
          onLoop.accept(path, new FileSystemLoopException(path.toString()));
        } else {
          targetRegistrationPath.paths.add(path);
        }
      } catch (IOException e) {
      } finally {
        lock.unlock();
      }
    }
  }

  /**
   * Removes the symlink from monitoring. If there are no remaining targets in the parent directory,
   * then we remove the parent directory from monitoring.
   *
   * @param path The symlink base to stop monitoring
   */
  void remove(final Path path) {
    if (lock.lock()) {
      try {
        Path target = null;
        {
          final Iterator<Entry<Path, RegisteredPath>> it =
              watchedSymlinksByTarget.entrySet().iterator();
          while (it.hasNext() && target == null) {
            final Entry<Path, RegisteredPath> entry = it.next();
            if (entry.getValue().paths.remove(path)) {
              target = entry.getKey();
            }
          }
        }
        if (target != null) {
          final Path removalPath = Files.isDirectory(target) ? target : target.getParent();
          final RegisteredPath targetRegisteredPath = watchedSymlinksByTarget.get(target);
          if (targetRegisteredPath != null) {
            targetRegisteredPath.paths.remove(path);
            if (targetRegisteredPath.paths.isEmpty()) {
              watchedSymlinksByTarget.remove(target);
              final RegisteredPath registeredPath = watchedSymlinksByDirectory.get(removalPath);
              if (registeredPath != null) {
                registeredPath.paths.remove(target);
                if (registeredPath.paths.isEmpty()) {
                  watcher.unregister(removalPath);
                  watchedSymlinksByDirectory.remove(removalPath);
                }
              }
            }
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  interface OnLoop {
    void accept(final Path symlink, final IOException exception);
  }
}
