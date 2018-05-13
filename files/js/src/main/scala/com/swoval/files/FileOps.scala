package com.swoval.files

import java.io.File
import java.io.FileFilter
import java.nio.file.Path
import java.util.ArrayList
import java.util.Iterator
import java.util.List

private[files] object FileOps {

  val AllPass: FileFilter = new FileFilter() {
    override def accept(file: File): Boolean = true
  }

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @return Array of paths
   */
  def list(path: Path, recursive: Boolean): List[Path] =
    list(path, recursive, AllPass)

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @param filter Include only paths accepted by the filter
   * @return Array of Paths
   */
  def list(path: Path, recursive: Boolean, filter: FileFilter): List[Path] = {
    val res: List[Path] = new ArrayList[Path]()
    listImpl(path.toFile(), recursive, filter, res)
    res
  }

  private def listImpl(file: File,
                       recursive: Boolean,
                       filter: FileFilter,
                       result: List[Path]): Unit = {
    val files: Array[File] = file.listFiles(filter)
    if (files != null) {
      var i: Int = 0
      while (i < files.length) {
        val f: File = files(i)
        result.add(f.toPath())
        if (f.isDirectory && recursive) listImpl(f, recursive, filter, result)
        i += 1
      }
    }
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
