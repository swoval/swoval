package com.swoval.files

import java.io.File
import java.nio.file.Path

/**
 * Represents a file that will be returned by [[QuickList.lis]]. Provides fast [[QuickFile#isDirectory]] and [[QuickFile#isFile]] methods that should not call stat (or the
 * non-POSIX equivalent) on the * underlying file. Can be converted to a [[java.io.File]] or
 * [[java.nio.file.Path]] with [[QuickFile.toFil]] and [[QuickFile#toPath]].
 */
trait QuickFile {

  /**
   * Returns the fully resolved file name
   *
   * @return the fully resolved file name
   */
  def getFileName(): String

  /**
   * Returns true if this was a directory at the time time of listing. This may become inconsistent
   * if the QuickFile is cached
   *
   * @return true when the QuickFile is a directory
   */
  def isDirectory(): Boolean

  /**
   * Returns true if this was a regular file at the time time of listing. This may become
   * inconsistent if the QuickFile is cached
   *
   * @return true when the QuickFile is a file
   */
  def isFile(): Boolean

  /**
   * Returns an instance of [[File]]. Typically the implementation of [[QuickFile]] while
   * extend [[File]]. This method will then just cast the instance to [[File]]. Because the
   * [[QuickFile.isDirector]] and [[QuickFile#isFile]] methods will generally cache the
   * value of the native file result returned by readdir (posix) or FindNextFile (windows) and use
   * this value to compute [[QuickFile.isDirector]] and [[QuickFile#isFile]], the returned
   * [[File]] is generally unsuitable to be used as a persistent value. Instead, use [[QuickFile.toFil]].
   */
  def asFile(): File

  /**
   * Returns an instance of [[File]]. The instance should not override [[File.isDirector]]
   * or [[File.isFil]] which makes it safe to persist.
   *
   * @return an instance of [[File]]
   */
  def toFile(): File

  /**
   * Returns an instance of [[Path]].
   *
   * @return an instance of [[Path]]
   */
  def toPath(): Path

}

private[files] class QuickFileImpl(name: String, private val kind: Int)
    extends File(name)
    with QuickFile {

  override def getFileName(): String = super.toString

  override def isDirectory(): Boolean =
    if (kind == QuickListerImpl.UNKNOWN) super.isDirectory
    else kind == QuickListerImpl.DIRECTORY

  override def isFile(): Boolean =
    if (kind == QuickListerImpl.UNKNOWN) super.isFile
    else kind == QuickListerImpl.FILE

  override def asFile(): File = this

  override def toFile(): File = new File(getFileName)

  override def toString(): String = "QuickFile(" + getFileName + ")"

}
