package com.swoval.files

import java.nio.file.{ Files, Path, Paths }

import com.swoval.files.test._
import com.swoval.functional.Filter
import com.swoval.functional.Filters.AllPass
import com.swoval.runtime.Platform
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._

object FileTreeViewTest {
  implicit class RepositoryOps[T <: AnyRef](val d: DirectoryView) {
    def ls(path: Path, recursive: Boolean, filter: Filter[_ >: TypedPath]): Seq[Path] =
      d.list(path, if (recursive) Integer.MAX_VALUE else 0, filter).asScala.map(_.getPath)
    def ls(recursive: Boolean, filter: Filter[_ >: TypedPath]): Seq[Path] =
      d.list(if (recursive) Integer.MAX_VALUE else 0, filter).asScala.map(_.getPath)
  }
}
import com.swoval.files.FileTreeViewTest._
class FileTreeViewTest(newFileTreeView: (Path, Int, Boolean) => DirectoryView) extends TestSuite {
  def newFileTreeView(path: Path): DirectoryView = newFileTreeView(path, Integer.MAX_VALUE, false)
  def newFileTreeView(path: Path, maxDepth: Int): DirectoryView =
    newFileTreeView(path, maxDepth, true)

  def pathFilter(f: TypedPath => Boolean): Filter[TypedPath] = (tp: TypedPath) => f(tp)
  val tests = Tests {
    'list - {
      "empty" - withTempDirectorySync { dir =>
        assert(newFileTreeView(dir).ls(dir, recursive = true, AllPass).isEmpty)
      }
      "files" - {
        'parent - {
          withTempFileSync { file =>
            val parent = file.getParent
            newFileTreeView(parent).ls(parent, recursive = true, AllPass) === Seq(file)
          }
        }
        'directly - {
          withTempFileSync { file =>
            val parent = file.getParent
            newFileTreeView(parent).ls(file, recursive = true, AllPass) === Seq(file)
          }
        }
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: Path): Filter[TypedPath] =
              (e: TypedPath) => e.getPath.getParent == dir
            val directory = newFileTreeView(dir)
            directory.ls(recursive = true, parentEquals(dir)) === Seq(subdir)
            directory.ls(recursive = true, parentEquals(subdir)) === Seq(f)
            directory.ls(recursive = true, AllPass) === Seq(subdir, f)
          }
        }
      }
      "directories" - {
        "non-recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { _ =>
                newFileTreeView(dir).ls(recursive = false, AllPass) === Set(f, subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                newFileTreeView(dir).ls(recursive = true, AllPass) === Set(f, f2, subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            newFileTreeView(dir).ls(subdir, recursive = true, AllPass) === Seq(f)
            assert(
              newFileTreeView(dir)
                .ls(Paths.get(s"$subdir.1"), recursive = true, AllPass)
                .isEmpty)
          }
        }
      }
      "filter" - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectorySync(dir) { subdir =>
            newFileTreeView(dir)
              .ls(recursive = true, pathFilter(!(_: TypedPath).isDirectory)) === Seq(f)
            newFileTreeView(dir)
              .ls(recursive = true, pathFilter((_: TypedPath).isDirectory)) === Seq(subdir)
          }
        }
      }
    }
    'recursive - withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        withTempFileSync(subdir) { f =>
          assert(f.exists)
          newFileTreeView(subdir).ls(subdir, recursive = true, AllPass) === Seq(f)
          newFileTreeView(dir, 0).ls(dir, recursive = true, AllPass) === Seq(subdir)
          newFileTreeView(dir).ls(dir, recursive = true, AllPass) === Set(subdir, f)
          newFileTreeView(dir).ls(dir, recursive = false, AllPass) === Seq(subdir)
        }
      }
    }
    'depth - {
      'nonnegative - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { file =>
            newFileTreeView(dir, 0).ls(dir, recursive = true, AllPass) === Set(subdir)
            newFileTreeView(dir, 1).ls(dir, recursive = true, AllPass) === Set(subdir, file)
          }
        }
      }
      'negative - {
        'file - withTempFileSync { file =>
          newFileTreeView(file, -1).ls(file, recursive = true, AllPass) === Seq(file)
        }
        'directory - withTempDirectorySync { dir =>
          newFileTreeView(dir, -1).ls(dir, recursive = true, AllPass) === Seq(dir)
        }
        'parameter - withTempFileSync { file =>
          val dir = file.getParent
          val directory = newFileTreeView(dir, Integer.MAX_VALUE)
          directory.list(dir, -1, AllPass).asScala.map(_.getPath) === Seq(dir)
          directory.list(dir, 0, AllPass).asScala.map(_.getPath) === Seq(file)
        }
      }
    }
    'init - {
      'accessDenied - {
        if (!Platform.isWin) withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { file =>
              subdir.toFile.setReadable(false)
              try {
                val directory = newFileTreeView(dir)
                directory.ls(dir, recursive = true, AllPass) === Seq(subdir)
              } finally {
                subdir.toFile.setReadable(true)
              }
            }
          }
        }
      }
    }
    'symlinks - {
      'file - withTempFileSync { file =>
        val parent = file.getParent
        val link = Files.createSymbolicLink(parent.resolve("link"), file)
        newFileTreeView(parent).ls(parent, recursive = true, AllPass) === Set(file, link)
      }
      'directory - withTempDirectory { dir =>
        withTempDirectorySync { otherDir =>
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          val file = otherDir.resolve("file").createFile()
          val dirFile = dir.resolve("link").resolve("file")
          newFileTreeView(dir, Integer.MAX_VALUE, true).ls(dir, recursive = true, AllPass) === Set(
            link,
            dirFile)
        }
      }
      'loop - {
        'initial - withTempDirectory { dir =>
          withTempDirectorySync { otherDir =>
            val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
            val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
            newFileTreeView(dir, Integer.MAX_VALUE, true)
              .ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                          dirToOtherDirLink.resolve("dir"))
          }
        }
      }
    }
  }
}
object DirectoryFileTreeViewTest extends FileTreeViewTest(FileTreeViews.of)
object NioFileTreeViewTest
    extends FileTreeViewTest(
      (path: Path, depth: Int, followLinks: Boolean) =>
        new CachedDirectoryImpl[Path](path,
                                      path,
                                      p => p.getPath,
                                      depth,
                                      AllPass,
                                      FileTreeViews.getNio(followLinks)).init())
