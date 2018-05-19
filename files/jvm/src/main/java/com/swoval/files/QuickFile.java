package com.swoval.files;

import java.io.File;
import java.nio.file.Path;

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
   * Returns an instance of {@link File}. Typically the implementation of {@link QuickFile} while
   * extend {@link File}. This method will then just cast the instance to {@link File}. Because the
   * {@link QuickFile#isDirectory} and {@link QuickFile#isFile} methods will generally cache the
   * value of the native file result returned by readdir (posix) or FindNextFile (windows) and use
   * this value to compute {@link QuickFile#isDirectory} and {@link QuickFile#isFile}, the returned
   * {@link File} is generally unsuitable to be used as a persistent value. Instead, use {@link
   * QuickFile#toFile}.
   */
  File asFile();

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

class QuickFileImpl extends File implements QuickFile {
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
    return kind == QuickListerImpl.UNKNOWN
        ? super.isDirectory()
        : kind == QuickListerImpl.DIRECTORY;
  }

  @Override
  public boolean isFile() {
    return kind == QuickListerImpl.UNKNOWN ? super.isFile() : kind == QuickListerImpl.FILE;
  }

  @Override
  public File asFile() {
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
}
