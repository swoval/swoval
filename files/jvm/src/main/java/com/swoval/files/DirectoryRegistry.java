package com.swoval.files;

import static java.util.Map.Entry;

import com.swoval.functional.Filter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

interface DirectoryRegistry extends Filter<Path>, AutoCloseable {
  boolean addDirectory(final Path path, final int maxDepth);

  int maxDepthFor(final Path path);

  Map<Path, Integer> registered();

  void removeDirectory(final Path path);

  boolean acceptPrefix(final Path path);

  @Override
  void close();
}

class DirectoryRegistries {
  private DirectoryRegistries() {}

  static Filter<TypedPath> toTypedPathFilter(final DirectoryRegistry registry) {
    return new Filter<TypedPath>() {
      @Override
      public boolean accept(final TypedPath typedPath) {
        return registry.accept(typedPath.getPath());
      }
    };
  }
}

class DirectoryRegistryImpl implements DirectoryRegistry {
  private final Map<Path, RegisteredDirectory> registeredDirectoriesByPath =
      new ConcurrentHashMap<>();
  private final Object lock = new Object();

  @Override
  public boolean addDirectory(final Path path, final int maxDepth) {
    synchronized (lock) {
      final RegisteredDirectory registeredDirectory = registeredDirectoriesByPath.get(path);
      if (registeredDirectory == null || maxDepth > registeredDirectory.maxDepth) {
        registeredDirectoriesByPath.put(path, new RegisteredDirectory(path, maxDepth));
        return true;
      } else {
        return false;
      }
    }
  }

  @Override
  public int maxDepthFor(final Path path) {
    synchronized (lock) {
      int maxDepth = Integer.MIN_VALUE;
      final Iterator<RegisteredDirectory> it = registeredDirectoriesByPath.values().iterator();
      while (it.hasNext()) {
        final RegisteredDirectory dir = it.next();
        if (path.startsWith(dir.path)) {
          final int depth = dir.path.equals(path) ? 0 : dir.path.relativize(path).getNameCount();
          final int possibleMaxDepth = dir.maxDepth - depth;
          if (possibleMaxDepth > maxDepth) {
            maxDepth = possibleMaxDepth;
          }
        }
      }
      return maxDepth;
    }
  }

  @Override
  public Map<Path, Integer> registered() {
    synchronized (lock) {
      final Map<Path, Integer> result = new HashMap<>();
      final Iterator<RegisteredDirectory> it = registeredDirectoriesByPath.values().iterator();
      while (it.hasNext()) {
        final RegisteredDirectory dir = it.next();
        result.put(dir.path, dir.maxDepth);
      }
      return result;
    }
  }

  @Override
  public void removeDirectory(final Path path) {
    synchronized (lock) {
      registeredDirectoriesByPath.remove(path);
    }
  }

  private boolean acceptImpl(final Path path, final boolean acceptPrefix) {
    synchronized (lock) {
      boolean result = false;
      final Iterator<Entry<Path, RegisteredDirectory>> it =
          new ArrayList<>(registeredDirectoriesByPath.entrySet()).iterator();
      while (!result && it.hasNext()) {
        final Entry<Path, RegisteredDirectory> entry = it.next();
        final RegisteredDirectory registeredDirectory = entry.getValue();
        final Path watchPath = entry.getKey();
        if (acceptPrefix && watchPath.startsWith(path)) {
          result = true;
        } else if (path.startsWith(watchPath)) {
          result = registeredDirectory.accept(path);
        }
      }
      return result;
    }
  }

  @Override
  public boolean accept(final Path path) {
    return acceptImpl(path, false);
  }

  @Override
  public boolean acceptPrefix(final Path path) {
    return acceptImpl(path, true);
  }

  @Override
  public void close() {
    registeredDirectoriesByPath.clear();
  }

  private static class RegisteredDirectory {
    final Path path;
    final int maxDepth;
    final int compMaxDepth;

    RegisteredDirectory(final Path path, final int maxDepth) {
      this.path = path;
      this.maxDepth = maxDepth;
      compMaxDepth = maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth + 1;
    }

    public boolean accept(final Path path) {
      return path.startsWith(this.path)
          && (path.equals(this.path) || this.path.relativize(path).getNameCount() <= compMaxDepth);
    }

    @Override
    public String toString() {
      return "RegisteredDirectory(path = " + path + ", depth = " + maxDepth + ")";
    }
  }
}
