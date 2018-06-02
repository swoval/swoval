package com.swoval.files;

/**
 * An object that represents a file, directory or symbolic link. It is possible for a file to be
 * both a SymbolicLink and Directory or a File, but it should not be a Directory and a File
 */
public interface FileType {

  /**
   * Returns true when the file represents a directory. This directory may be a symbolic link.
   * @return true when the file represents a directory
   */
  boolean isDirectory();
  /**
   * Returns true when the file represents a file. This file may be a symbolic link.
   * @return true when the file represents a file
   */
  boolean isFile();
  /**
   * Returns true when the file represents a symbolic link
   * @return true when the file represents a symbolic link
   */
  boolean isSymbolicLink();
}
