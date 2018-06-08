package com.swoval.files

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchEvent.Modifier
import java.nio.file.WatchKey
import java.util.Iterator
import QuickFileImpl._

trait PathWithFileType extends Path with FileType

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
   * Returns an instance of [[File]]. Typically the implementation of [[QuickFile]] while
   * extend [[File]]. This method will then just cast the instance to [[File]]. Because the
   * [[QuickFile.isDirectory]] and [[QuickFile.isFile]] methods will generally cache the
   * value of the native file result returned by readdir (posix) or FindNextFile (windows) and use
   * this value to compute [[QuickFile.isDirectory]] and [[QuickFile.isFile]], the returned
   * [[File]] is generally unsuitable to be used as a persistent value. Instead, use [[QuickFile.toFile]].
   */
  def asFile(): FileWithFileType

  /**
   * Returns a [[PathWithFileType]] instance. It should not stat the file to implement [[FileType]]
   *
   * @return an instance of [[PathWithFileType]]
   */
  def asPath(): PathWithFileType

  /**
   * Returns an instance of [[File]]. The instance should not override [[File.isDirectory]]
   * or [[File.isFile]] which makes it safe to persist.
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

private[files] object QuickFileImpl {

  private[files] class PathWithFileTypeImpl(private val self: QuickFileImpl, private val path: Path)
      extends PathWithFileType {

    override def isDirectory(): Boolean = self.isDirectory

    override def isFile(): Boolean = self.isFile

    override def isSymbolicLink(): Boolean = self.isSymbolicLink

    override def getFileSystem(): FileSystem = path.getFileSystem

    override def isAbsolute(): Boolean = path.isAbsolute

    override def getRoot(): Path = path.getRoot

    override def getFileName(): Path = path.getFileName

    override def getParent(): Path = path.getParent

    override def getNameCount(): Int = path.getNameCount

    override def getName(index: Int): Path = path.getName(index)

    override def subpath(beginIndex: Int, endIndex: Int): Path =
      path.subpath(beginIndex, endIndex)

    override def startsWith(other: Path): Boolean = path.startsWith(other)

    override def startsWith(other: String): Boolean = path.startsWith(other)

    override def endsWith(other: Path): Boolean = path.endsWith(other)

    override def endsWith(other: String): Boolean = path.endsWith(other)

    override def normalize(): Path =
      new PathWithFileTypeImpl(self, path.normalize())

    override def resolve(other: Path): Path = path.resolve(other)

    override def resolve(other: String): Path = path.resolve(other)

    override def resolveSibling(other: Path): Path = path.resolveSibling(other)

    override def resolveSibling(other: String): Path =
      path.resolveSibling(other)

    override def relativize(other: Path): Path = path.relativize(other)

    override def toUri(): URI = path.toUri()

    override def toAbsolutePath(): Path = path.toAbsolutePath()

    override def toRealPath(options: Array[LinkOption]): Path =
      path.toRealPath()

    override def toFile(): File = self.toFile()

    override def register(watcher: java.nio.file.WatchService,
                          events: Array[WatchEvent.Kind[_]],
                          modifiers: Array[Modifier]): WatchKey =
      throw new UnsupportedOperationException("Can't register a delegate path with a watch service")

    override def register(watcher: java.nio.file.WatchService,
                          events: Array[WatchEvent.Kind[_]]): WatchKey =
      throw new UnsupportedOperationException("Can't register a delegate path with a watch service")

    override def iterator(): Iterator[Path] = path.iterator()

    override def compareTo(other: Path): Int = path.compareTo(other)

    override def equals(other: Any): Boolean =
      if (other.isInstanceOf[PathWithFileTypeImpl]) {
        val that: PathWithFileTypeImpl =
          other.asInstanceOf[PathWithFileTypeImpl]
        this.path == that.path
      } else if (other.isInstanceOf[Path]) {
        this.path == other
      } else {
        throw new UnsupportedOperationException(
          "Tried to compare an invalid path class " + other.getClass)
      }

    override def hashCode(): Int = path.hashCode

    override def toString(): String = path.toString

  }

}

private[files] class QuickFileImpl(name: String, private val kind: Int)
    extends FileWithFileType(name)
    with QuickFile {

  override def getFileName(): String = super.toString

  override def isDirectory(): Boolean =
    if (is(QuickListerImpl.UNKNOWN)) super.isDirectory
    else is(QuickListerImpl.DIRECTORY)

  override def isFile(): Boolean =
    if (is(QuickListerImpl.UNKNOWN)) super.isFile else is(QuickListerImpl.FILE)

  override def isSymbolicLink(): Boolean =
    if (is(QuickListerImpl.UNKNOWN)) Files.isSymbolicLink(toPath())
    else is(QuickListerImpl.LINK)

  override def asPath(): PathWithFileType = {
    val path: Path = toPath()
    val self: QuickFileImpl = this
    new PathWithFileTypeImpl(self, path)
  }

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
