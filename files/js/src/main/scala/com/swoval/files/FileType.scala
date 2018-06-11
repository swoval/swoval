package com.swoval.files

/**
 * An object that represents a file, directory or symbolic link. It is possible for a file to be
 * both a SymbolicLink and Directory or a File, but it should not be a Directory and a File
 */
trait FileType {

  /**
   * Returns true when the file represents a directory. This directory may be a symbolic link.
   *
   * @return true when the file represents a directory
   */
  def isDirectory(): Boolean

  /**
   * Returns true when the file represents a file. This file may be a symbolic link.
   *
   * @return true when the file represents a file
   */
  def isFile(): Boolean

  /**
   * Returns true when the file represents a symbolic link
   *
   * @return true when the file represents a symbolic link
   */
  def isSymbolicLink(): Boolean

}
