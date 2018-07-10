package com.swoval.files

import java.io.IOException
import java.nio.file.Files

import com.swoval.files.test._
import com.swoval.functional.Filters.AllPass
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._

object DataViewTest extends TestSuite {
  import FileTreeViewTest.RepositoryOps
  val tests = Tests {
    'converter - {
      'exceptions - {
        'directory - withTempFileSync { file =>
          val parent = file.getParent
          val dir = FileTreeViews.cached(parent, (p: TypedPath) => {
            if (p.isDirectory) throw new IOException("die")
            1: Integer
          }, Integer.MAX_VALUE)
          dir.getEntry.getValue.getOrElse(2) ==> 2
          dir.getEntry.getValue.left().getValue.getMessage ==> "die"
          dir.ls(recursive = true, AllPass) === Seq(file)
        }
        'subdirectory - withTempDirectorySync { dir =>
          val subdir = Files.createDirectory(dir.resolve("subdir"))
          val directory = FileTreeViews.cached(dir, (p: TypedPath) => {
            if (p.getPath.toString.contains("subdir")) throw new IOException("die")
            1: Integer
          }, 0)
          directory.getEntry.getValue.getOrElse(2) ==> 1
          directory
            .listEntries(Integer.MAX_VALUE, AllPass)
            .asScala
            .map(e => e.getPath -> e.getValue.getOrElse(3))
            .toSeq === Seq(subdir -> 3)
        }
        'file - withTempFileSync { file =>
          val parent = file.getParent
          val dir = FileTreeViews.cached(parent, (p: TypedPath) => {
            if (!p.isDirectory) throw new IOException("die")
            1: Integer
          }, Integer.MAX_VALUE)
          dir.getEntry.getValue.getOrElse(2) ==> 1
          dir
            .listEntries(parent, Integer.MAX_VALUE, AllPass)
            .asScala
            .map(e => e.getPath -> e.getValue.getOrElse(3))
            .toSeq === Seq(file -> 3)
        }
      }
    }
  }
}
