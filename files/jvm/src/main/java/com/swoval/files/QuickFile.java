package com.swoval.files;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.util.Iterator;

interface PathWithFileType extends Path, FileType {}

abstract class FileWithFileType extends File implements FileType {
  public FileWithFileType(final String name) {
    super(name);
  }
}

/**
 * Represents a file that will be returned by {@link QuickList#list}. Provides fast {@link
 * QuickFile#isDirectory} and {@link QuickFile#isFile} methods that should not call stat (or the
 * non-POSIX equivalent) on the * underlying file. Can be converted to a {@link java.io.File} or
 * {@link java.nio.file.Path} with {@link QuickFile#toFile} and {@link QuickFile#toPath}.
 */
public interface QuickFile {
  /**
   * Returns the fully resolved file name
   *
   * @return the fully resolved file name
   */
  String getFileName();

  /**
   * Returns true if this was a directory at the time time of listing. This may become inconsistent
   * if the QuickFile is cached
   *
   * @return true when the QuickFile is a directory
   */
  boolean isDirectory();
  /**
   * Returns true if this was a regular file at the time time of listing. This may become
   * inconsistent if the QuickFile is cached
   *
   * @return true when the QuickFile is a file
   */
  boolean isFile();

  /**
   * Returns true if this was a symbolic link at the time time of listing. This may become
   * inconsistent if the QuickFile is cached
   *
   * @return true when the QuickFile is a symbolic link
   */
  boolean isSymbolicLink();

  /**
   * Returns an instance of {@link File}. Typically the implementation of {@link QuickFile} while
   * extend {@link File}. This method will then just cast the instance to {@link File}. Because the
   * {@link QuickFile#isDirectory} and {@link QuickFile#isFile} methods will generally cache the
   * value of the native file result returned by readdir (posix) or FindNextFile (windows) and use
   * this value to compute {@link QuickFile#isDirectory} and {@link QuickFile#isFile}, the returned
   * {@link File} is generally unsuitable to be used as a persistent value. Instead, use {@link
   * QuickFile#toFile}.
   */
  FileWithFileType asFile();

  /**
   * Returns a {@link PathWithFileType} instance. It should not stat the file to implement {@link
   * FileType}
   *
   * @return an instance of {@link PathWithFileType}
   */
  PathWithFileType asPath();

  /**
   * Returns an instance of {@link File}. The instance should not override {@link File#isDirectory}
   * or {@link File#isFile} which makes it safe to persist.
   *
   * @return an instance of {@link File}
   */
  File toFile();

  /**
   * Returns an instance of {@link Path}.
   *
   * @return an instance of {@link Path}
   */
  Path toPath();
}

class QuickFileImpl extends FileWithFileType implements QuickFile {
  private final int kind;

  QuickFileImpl(final String name, final int kind) {
    super(name);
    this.kind = kind;
  }

  @Override
  public String getFileName() {
    return super.toString();
  }

  @Override
  public boolean isDirectory() {
    return is(QuickListerImpl.UNKNOWN) ? super.isDirectory() : is(QuickListerImpl.DIRECTORY);
  }

  @Override
  public boolean isFile() {
    return is(QuickListerImpl.UNKNOWN) ? super.isFile() : is(QuickListerImpl.FILE);
  }

  @Override
  public boolean isSymbolicLink() {
    return is(QuickListerImpl.UNKNOWN) ? Files.isSymbolicLink(toPath()) : is(QuickListerImpl.LINK);
  }

  @Override
  public PathWithFileType asPath() {
    final Path path = toPath();
    final QuickFileImpl self = this;
    return new PathWithFileTypeImpl(self, path);
  }

  @Override
  public FileWithFileType asFile() {
    return this;
  }

  @Override
  public File toFile() {
    return new File(getFileName());
  }

  @Override
  public String toString() {
    return "QuickFile(" + getFileName() + ")";
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof QuickFile
        && this.getFileName().equals(((QuickFile) other).getFileName());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }

  private boolean is(final int kind) {
    return (this.kind & kind) != 0;
  }

  static class PathWithFileTypeImpl implements PathWithFileType {
    private final QuickFileImpl self;
    private final Path path;

    public PathWithFileTypeImpl(QuickFileImpl self, Path path) {
      this.self = self;
      this.path = path;
    }

    @Override
    public boolean isDirectory() {
      return self.isDirectory();
    }

    @Override
    public boolean isFile() {
      return self.isFile();
    }

    @Override
    public boolean isSymbolicLink() {
      return self.isSymbolicLink();
    }

    @Override
    public FileSystem getFileSystem() {
      return path.getFileSystem();
    }

    @Override
    public boolean isAbsolute() {
      return path.isAbsolute();
    }

    @Override
    public Path getRoot() {
      return path.getRoot();
    }

    @Override
    public Path getFileName() {
      return path.getFileName();
    }

    @Override
    public Path getParent() {
      return path.getParent();
    }

    @Override
    public int getNameCount() {
      return path.getNameCount();
    }

    @Override
    public Path getName(int index) {
      return path.getName(index);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
      return path.subpath(beginIndex, endIndex);
    }

    @Override
    public boolean startsWith(Path other) {
      return path.startsWith(other);
    }

    @Override
    public boolean startsWith(String other) {
      return path.startsWith(other);
    }

    @Override
    public boolean endsWith(Path other) {
      return path.endsWith(other);
    }

    @Override
    public boolean endsWith(String other) {
      return path.endsWith(other);
    }

    @Override
    public Path normalize() {
      return new PathWithFileTypeImpl(self, path.normalize());
    }

    @Override
    public Path resolve(Path other) {
      return path.resolve(other);
    }

    @Override
    public Path resolve(String other) {
      return path.resolve(other);
    }

    @Override
    public Path resolveSibling(Path other) {
      return path.resolveSibling(other);
    }

    @Override
    public Path resolveSibling(String other) {
      return path.resolveSibling(other);
    }

    @Override
    public Path relativize(Path other) {
      return path.relativize(other);
    }

    @Override
    public URI toUri() {
      return path.toUri();
    }

    @Override
    public Path toAbsolutePath() {
      return path.toAbsolutePath();
    }

    @Override
    public Path toRealPath(LinkOption[] options) throws IOException {
      return path.toRealPath();
    }

    @Override
    public File toFile() {
      return self.toFile();
    }

    @Override
    public WatchKey register(
        java.nio.file.WatchService watcher, WatchEvent.Kind<?>[] events, Modifier[] modifiers)
        throws UnsupportedOperationException {
      throw new UnsupportedOperationException(
          "Can't register a delegate path with a watch service");
    }

    @Override
    public WatchKey register(java.nio.file.WatchService watcher, WatchEvent.Kind<?>[] events)
        throws UnsupportedOperationException {
      throw new UnsupportedOperationException(
          "Can't register a delegate path with a watch service");
    }

    @Override
    public Iterator<Path> iterator() {
      return path.iterator();
    }

    @Override
    public int compareTo(Path other) {
      return path.compareTo(other);
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof PathWithFileTypeImpl) {
        PathWithFileTypeImpl that = (PathWithFileTypeImpl) other;
        return this.path.equals(that.path);
      } else if (other instanceof Path) {
        return this.path.equals(other);
      } else {
        throw new UnsupportedOperationException(
            "Tried to compare an invalid path class " + other.getClass());
      }
    }

    @Override
    public int hashCode() {
      return path.hashCode();
    }

    @Override
    public String toString() {
      return path.toString();
    }
  }
}
