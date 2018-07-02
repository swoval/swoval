package com.swoval.files;

import com.swoval.functional.Filter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import static java.util.Map.Entry;

class DirectoryRegistry implements Filter<Path> {
  private final Map<Path, RegisteredDirectory> registeredDirectoriesByPath = new HashMap<>();

  public List<Path> registeredDirectories() {
    return new ArrayList<>(registeredDirectoriesByPath.keySet());
  }

  public void addDirectory(final Path path, final int maxDepth) {
    final RegisteredDirectory registeredDirectory = registeredDirectoriesByPath.get(path);
    if (registeredDirectory == null || maxDepth > registeredDirectory.maxDepth) {
      registeredDirectoriesByPath.put(path, new RegisteredDirectory(path, maxDepth));
    }
  }

  public int maxDepthFor(final Path path) {
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

  public void removeDirectory(final Path path) {
    final RegisteredDirectory registeredDirectory = registeredDirectoriesByPath.remove(path);
  }

  @Override
  public boolean accept(final Path path) {
    boolean result = false;
    final Iterator<Entry<Path, RegisteredDirectory>> it =
        registeredDirectoriesByPath.entrySet().iterator();
    while (!result && it.hasNext()) {
      final Entry<Path, RegisteredDirectory> entry = it.next();
      final RegisteredDirectory registeredDirectory = entry.getValue();
      final Path watchPath = entry.getKey();
      if (watchPath.startsWith(path)) {
        result = true;
      } else if (path.startsWith(watchPath)) {
        result = registeredDirectory.accept(path);
      }
    }
    return result;
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
