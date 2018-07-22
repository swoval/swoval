// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import java.io.File
import java.nio.file.{ FileSystemLoopException, NoSuchFileException, Path, Paths }
import java.nio.file.attribute.BasicFileAttributes
import java.util.{ ArrayList, HashSet, Iterator, List, Set }

import com.swoval.files.SimpleFileTreeView._
import com.swoval.functional.Filter

import scala.beans.BeanProperty

object SimpleFileTreeView {

  /*
   * These constants must be kept in sync with the native quick list implementation
   */

  val UNKNOWN: Int = Entries.UNKNOWN

  val DIRECTORY: Int = Entries.DIRECTORY

  val FILE: Int = Entries.FILE

  val LINK: Int = Entries.LINK

  val NONEXISTENT: Int = Entries.NONEXISTENT

  class ListResults {

    @BeanProperty
    val directories: List[String] = new ArrayList()

    @BeanProperty
    val files: List[String] = new ArrayList()

    @BeanProperty
    val symlinks: List[String] = new ArrayList()

    def addDir(dir: String): Unit = {
      directories.add(dir)
    }

    def addFile(file: String): Unit = {
      files.add(file)
    }

    def addSymlink(link: String): Unit = {
      symlinks.add(link)
    }

    override def toString(): String =
      "ListResults(\n  directories = " + directories + ",\n  files = " +
        files +
        ", \n  symlinks = " +
        symlinks +
        "\n)"

  }

}

class SimpleFileTreeView(private val directoryLister: DirectoryLister,
                         private val followLinks: Boolean)
    extends FileTreeView {

  override def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath] = {
    val result: List[TypedPath] = new ArrayList[TypedPath]()
    val visited: Set[Path] =
      if ((followLinks && maxDepth > 0)) new HashSet[Path]() else null
    listDirImpl(path, 1, maxDepth, result, filter, visited)
    result
  }

  override def close(): Unit = {}

  private def getSymbolicLinkTargetKind(path: Path, followLinks: Boolean): Int =
    if (followLinks) {
      try {
        val attrs: BasicFileAttributes = NioWrappers.readAttributes(path)
        LINK |
          (if (attrs.isDirectory) DIRECTORY
           else if (attrs.isRegularFile) FILE
           else UNKNOWN)
      } catch {
        case e: NoSuchFileException => NONEXISTENT

      }
    } else {
      LINK
    }

  private def listDirImpl(dir: Path,
                          depth: Int,
                          maxDepth: Int,
                          result: List[TypedPath],
                          filter: Filter[_ >: TypedPath],
                          visited: Set[Path]): Unit = {
    if (visited != null) visited.add(dir)
    val listResults: SimpleFileTreeView.ListResults =
      directoryLister.apply(dir.toString, followLinks)
    val it: Iterator[String] = listResults.getDirectories.iterator()
    while (it.hasNext) {
      val part: String = it.next()
      if (part.!=(".") && part.!=("..")) {
        val path: Path = Paths.get(dir + File.separator + part)
        val file: TypedPath = TypedPaths.get(path, DIRECTORY)
        if (filter.accept(file)) {
          result.add(file)
          if (depth < maxDepth) {
            listDirImpl(path, depth + 1, maxDepth, result, filter, visited)
          }
        }
      }
    }
    val fileIt: Iterator[String] = listResults.getFiles.iterator()
    while (fileIt.hasNext) {
      val typedPath: TypedPath =
        TypedPaths.get(Paths.get(dir + File.separator + fileIt.next()), FILE)
      if (filter.accept(typedPath)) {
        result.add(typedPath)
      }
    }
    val symlinkIt: Iterator[String] = listResults.getSymlinks.iterator()
    while (symlinkIt.hasNext) {
      val fileName: Path = Paths.get(dir + File.separator + symlinkIt.next())
      val typedPath: TypedPath =
        TypedPaths.get(fileName, getSymbolicLinkTargetKind(fileName, followLinks))
      if (filter.accept(typedPath)) {
        result.add(typedPath)
        if (typedPath.isDirectory && depth < maxDepth && visited != null) {
          if (visited.add(typedPath.getPath.toRealPath())) {
            listDirImpl(fileName, depth + 1, maxDepth, result, filter, visited)
          } else {
            throw new FileSystemLoopException(fileName.toString)
          }
        }
      }
    }
  }

}