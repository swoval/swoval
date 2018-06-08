package com.swoval.files

import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.nio.file.Path
import java.util.ArrayList
import java.util.Iterator
import java.util.List

private[files] object FileOps {

  val AllPass: FileFilter = new FileFilter() {
    override def accept(file: File): Boolean = true

    override def toString(): String = "AllPass"
  }

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @return Array of paths
   */
  def list(path: Path, recursive: Boolean): List[File] =
    list(path, recursive, AllPass)

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param maxDepth The maximum depth to traverse subdirectories
   * @param filter Include only paths accepted by the filter
   * @return Array of Paths
   */
  def list(path: Path, maxDepth: Int, filter: FileFilter): List[File] = {
    val result: List[File] = new ArrayList[File]()
    val it: Iterator[QuickFile] = QuickList
      .list(path, maxDepth, true)
      .iterator()
    while (it.hasNext) {
      val quickFile: QuickFile = it.next()
      if (filter.accept(quickFile.asFile())) {
        result.add(quickFile.toFile())
      }
    }
    result
  }

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @param filter Include only paths accepted by the filter
   * @return Array of Paths
   */
  def list(path: Path, recursive: Boolean, filter: FileFilter): List[File] = {
    list(path, if (recursive) Integer.MAX_VALUE else 0, filter)
  }

  /**
   * Returns the name components of a path in an array.
   *
   * @param path The path from which we extract the parts.
   * @return Empty array if path is an empty relative path, otherwise return the name parts.
   */
  def parts(path: Path): List[Path] = {
    val it: Iterator[Path] = path.iterator()
    val result: List[Path] = new ArrayList[Path]()
    while (it.hasNext) result.add(it.next())
    result
  }

}
