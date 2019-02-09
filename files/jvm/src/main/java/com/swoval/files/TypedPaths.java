package com.swoval.files;

import java.io.IOException;
import java.nio.file.Path;

/** Provides a default {@link TypedPath} implementation. */
public class TypedPaths {
  private TypedPaths() {}

  private abstract static class TypedPathImpl implements TypedPath {
    private final Path path;
    private Path realPath;

    TypedPathImpl(final Path path) {
      this.path = path;
    }

    @Override
    public Path getPath() {
      return path;
    }

    Path expanded() {
      synchronized (path) {
        if (realPath == null) {
          try {
            realPath = isSymbolicLink() ? path.toRealPath() : path;
            return realPath;
          } catch (final IOException e) {
            return path;
          }
        } else {
          return realPath;
        }
      }
    }

    @Override
    public String toString() {
      return "TypedPath(path: "
          + path
          + ", exists: "
          + exists()
          + ", isFile: "
          + isFile()
          + ", isDirectory: "
          + isDirectory()
          + ", isSymbolicLink: "
          + isSymbolicLink()
          + ")";
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

  static Path expanded(final TypedPath typedPath) {
    if (typedPath instanceof TypedPathImpl) {
      return ((TypedPathImpl) typedPath).expanded();
    } else {
      try {
        return typedPath.isSymbolicLink() ? typedPath.getPath().toRealPath() : typedPath.getPath();
      } catch (final IOException e) {
        return typedPath.getPath();
      }
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

  /**
   * Returns a typed path for the given path.
   *
   * @param path the path to convert to a typed path
   * @return the {@link TypedPath} for the input path.
   */
  public static TypedPath get(final Path path) {
    try {
      return get(path, Entries.getKind(path));
    } catch (final IOException e) {
      return get(path, Entries.NONEXISTENT);
    }
  }

  static TypedPath get(final Path path, final int kind) {
    return new TypedPathImpl(path.isAbsolute() ? path : path.toAbsolutePath()) {
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
