package com.swoval.files;

import java.nio.file.Path;

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
}
