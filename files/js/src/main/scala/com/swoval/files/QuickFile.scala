package com.swoval.files

import com.swoval.files.QuickListerImpl.DIRECTORY
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

abstract class FileWithFileType(name: String) extends File(name) with FileType

/**
 * Represents a file that will be returned by [[QuickList.list]]. Provides fast [[QuickFile.isDirectory]] and [[QuickFile.isFile]] methods that should not call stat (or the
 * non-POSIX equivalent) on the * underlying file. Can be converted to a [[java.io.File]] or
 * [[java.nio.file.Path]] with [[QuickFile.toFile]] and [[QuickFile.toPath]].
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
   * Returns true if this was a symbolic link at the time time of listing. This may become
   * inconsistent if the QuickFile is cached
   *
   * @return true when the QuickFile is a symbolic link
   */
  def isSymbolicLink(): Boolean

  /**
   * Returns an instance of [[FileWithFileType]]. Typically the implementation of [[QuickFile]] while extend [[FileWithFileType]]. This method will then just cast the instance
   * to [[java.io.File]]. Because the [[QuickFile.isDirectory]] and [[QuickFile.isFile]]
   * methods will generally cache the value of the native file result returned by readdir (posix) or
   * FindNextFile (windows) and use this value to compute [[QuickFile.isDirectory]] and [[QuickFile.isFile]], the returned [[FileWithFileType]] is generally unsuitable to be used as
   * a persistent value. Instead, use [[QuickFile.toFile]].
   *
   * @return An instance of FileWithFileType. This may just be a cast.
   */
  def asFile(): FileWithFileType

  /**
   * Returns an instance of [[java.io.File]]. The instance should not override [[java.io.File.isDirectory]] or [[java.io.File.isFile]] which makes it safe to persist.
   *
   * @return an instance of [[java.io.File]]
   */
  def toFile(): File

  /**
   * Returns an instance of [[java.nio.file.Path]].
   *
   * @return an instance of [[java.nio.file.Path]]
   */
  def toPath(): Path

}

private[files] class QuickFileImpl(name: String, private val kind: Int)
    extends FileWithFileType(name)
    with QuickFile {

  override def getFileName(): String = super.toString

  override def isDirectory(): Boolean =
    if (is(QuickListerImpl.UNKNOWN)) super.isDirectory else is(DIRECTORY)

  override def isFile(): Boolean =
    if (is(QuickListerImpl.UNKNOWN)) super.isFile else is(QuickListerImpl.FILE)

  override def isSymbolicLink(): Boolean =
    if (is(QuickListerImpl.UNKNOWN)) Files.isSymbolicLink(toPath())
    else is(QuickListerImpl.LINK)

  override def asFile(): FileWithFileType = this

  override def toFile(): File = new File(getFileName)

  override def toString(): String = "QuickFile(" + getFileName + ")"

  override def equals(other: Any): Boolean = other match {
    case other: QuickFile => this.getFileName == other.getFileName
    case _                => false

  }

  override def hashCode(): Int = toString.hashCode

  private def is(kind: Int): Boolean = (this.kind & kind) != 0

}
