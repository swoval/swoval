package com.swoval.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.Iterator
import java.util.List
import QuickListerImpl._
import scala.beans.{ BeanProperty, BooleanBeanProperty }

/**
 * Implementation class for [[QuickList.lis]]
 */
trait QuickLister {

  def list(path: Path, maxDepth: Int, followLinks: Boolean): List[QuickFile]

}

private[files] object QuickListerImpl {

  /*
   * These constants must be kept in sync with the native quick list implementation
   */

  val UNKNOWN: Int = 0

  val DIRECTORY: Int = 1

  val FILE: Int = 2

  val LINK: Int = 4

  val EOF: Int = 8

  val ENOENT: Int = -1

  val EACCES: Int = -2

  val ENOTDIR: Int = -3

  val ESUCCESS: Int = -4

  class ListResults {

    @BeanProperty
    val directories: List[String] = new ArrayList()

    @BeanProperty
    val files: List[String] = new ArrayList()

    def addDir(dir: String): Unit = {
      directories.add(dir)
    }

    def addFile(file: String): Unit = {
      files.add(file)
    }

  }

}

abstract private[files] class QuickListerImpl extends QuickLister {

  protected def listDir(dir: String, followLinks: Boolean): ListResults

  def list(path: Path, maxDepth: Int, followLinks: Boolean): List[QuickFile] = {
    val result: List[QuickFile] = new ArrayList[QuickFile]()
    listDirImpl(path.toString, 1, maxDepth, followLinks, result)
    result
  }

  private def listDirImpl(dir: String,
                          depth: Int,
                          maxDepth: Int,
                          followLinks: Boolean,
                          result: List[QuickFile]): Unit = {
    val listResults: QuickListerImpl.ListResults = listDir(dir, followLinks)
    val it: Iterator[String] = listResults.getDirectories.iterator()
    while (it.hasNext) {
      val part: String = it.next()
      if (part.!=(".") && part.!=("..")) {
        val name: String = dir + File.separator + part
        result.add(new QuickFileImpl(name, DIRECTORY))
        if (depth < maxDepth) {
          listDirImpl(name, depth + 1, maxDepth, followLinks, result)
        }
      }
    }
    val fileIt: Iterator[String] = listResults.getFiles.iterator()
    while (fileIt.hasNext) result.add(new QuickFileImpl(dir + File.separator + fileIt.next(), FILE))
  }

}
