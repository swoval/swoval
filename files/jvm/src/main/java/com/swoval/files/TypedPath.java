package com.swoval.files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A mix-in for an object that represents a file system path. Provides (possibly) fast accessors for
 * the type of the file.
 */
public interface TypedPath {
  /**
   * Return the path.
   *
   * @return the path.
   */
  Path getPath();

  /**
   * Does this path exist?
   *
   * @return true when the path exists.
   */
  boolean exists();
  /**
   * Is the path represented by this a directory?
   *
   * @return true if the underlying path is a directory
   */
  boolean isDirectory();
  /**
   * Is the path represented by this a regular file?
   *
   * @return true if the underlying path is a regular file
   */
  boolean isFile();
  /**
   * Is the path represented by this a symbolic link?
   *
   * @return true if the underlying path is a symbolic link
   */
  boolean isSymbolicLink();

  /**
   * Returns the real path when the target of the symbolic link if this path is a symbolic link and
   * the path itself otherwise.
   *
   * @return the real path.
   */
  Path toRealPath();
}

class TypedPaths {
  private TypedPaths() {}

  private abstract static class TypedPathImpl implements TypedPath {
    private final Path path;

    TypedPathImpl(final Path path) {
      this.path = path;
    }

    @Override
    public Path getPath() {
      return path;
    }

    @Override
    public Path toRealPath() {
      try {
        return isSymbolicLink() ? path.toRealPath() : path;
      } catch (final IOException e) {
        return path;
      }
    }

    @Override
    public String toString() {
      return "TypedPath(" + path + ", " + isSymbolicLink() + ", " + toRealPath() + ")";
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof TypedPath && ((TypedPath) other).getPath().equals(getPath());
    }

    @Override
    public int hashCode() {
      return getPath().hashCode();
    }
  }

  static TypedPath getDelegate(final Path path, final TypedPath typedPath) {
    return new TypedPathImpl(path) {
      @Override
      public boolean exists() {
        return typedPath.exists();
      }

      @Override
      public boolean isDirectory() {
        return typedPath.isDirectory();
      }

      @Override
      public boolean isFile() {
        return typedPath.isFile();
      }

      @Override
      public boolean isSymbolicLink() {
        return typedPath.isSymbolicLink();
      }
    };
  }

  static TypedPath get(final Path path) {
    try {
      return get(path, Entries.getKind(path));
    } catch (final IOException e) {
      return get(path, Entries.NONEXISTENT);
    }
  }

  static TypedPath get(final Path path, final int kind) {
    return new TypedPathImpl(path) {
      @Override
      public boolean exists() {
        return (kind & Entries.NONEXISTENT) == 0;
      }

      @Override
      public boolean isDirectory() {
        return (kind & Entries.DIRECTORY) != 0;
      }

      @Override
      public boolean isFile() {
        return (kind & Entries.FILE) != 0;
      }

      @Override
      public boolean isSymbolicLink() {
        return (kind & Entries.LINK) != 0;
      }
    };
  }
}
