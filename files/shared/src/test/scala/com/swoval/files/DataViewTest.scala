package com.swoval
package files

import java.io.IOException
import java.nio.file.{ Files, Paths }

import com.swoval.files.test._
import com.swoval.functional.Filters.AllPass
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._
import scala.concurrent.Future

object DataViewTest extends TestSuite {
  import FileTreeViewTest.RepositoryOps
  def directory: Future[Unit] = withTempFileSync { file =>
    val parent = file.getParent
    val dir = FileTreeViews.cached[Integer](parent, (p: TypedPath) => {
      if (p.isDirectory) throw new IOException("die")
      1: Integer
    }, Integer.MAX_VALUE, true)
    val either = dir.getEntry.getValue
    either.left.getValue.getMessage == "die"
    either.getOrElse(2) ==> 2
    dir.ls(recursive = true, AllPass) === Seq(file)
  }
  def subdirectory: Future[Unit] = withTempDirectorySync { dir =>
    val subdir = Files.createDirectory(dir.resolve("subdir"))
    val directory = FileTreeViews.cached(dir, (p: TypedPath) => {
      if (p.getPath.toString.contains("subdir")) throw new IOException("die")
      1: Integer
    }, 0, true)
    directory.getEntry.getValue.getOrElse(2) ==> 1
    directory
      .listEntries(Integer.MAX_VALUE, AllPass)
      .asScala
      .map(e => e.getPath -> e.getValue.getOrElse(3))
      .toSeq === Seq(subdir -> 3)
  }
  def file: Future[Unit] = withTempFileSync { file =>
    val parent = file.getParent
    val dir = FileTreeViews.cached(parent, (p: TypedPath) => {
      if (!p.isDirectory) throw new IOException("die")
      1: Integer
    }, Integer.MAX_VALUE, true)
    dir.getEntry.getValue.getOrElse(2) ==> 1
    dir
      .listEntries(parent, Integer.MAX_VALUE, AllPass)
      .asScala
      .map(e => e.getPath -> e.getValue.getOrElse(3))
      .toSeq === Seq(file -> 3)
  }
  val tests = Tests {
    'converter - {
      'exceptions - {
        'directory - directory
        'subdirectory - subdirectory
        'file - file
      }
    }
  }
}
