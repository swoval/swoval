package com.swoval.files

import java.io.{ File, IOException }
import java.nio.file.{ Files, Path, Paths }

import com.swoval.files.Directory.{ Entry, EntryFilter }
import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.EntryOps._
import com.swoval.files.Repositories.{ cached, of }
import com.swoval.files.test._
import com.swoval.runtime.Platform
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._

object RepositoryTest extends TestSuite {
  implicit class RepositoryOps[T <: AnyRef](val d: DataRepository[T] with DirectoryRepository) {
    def ls(recursive: Boolean, filter: EntryFilter[_ >: T]): Seq[Entry[T]] =
      d.list(d.getPath, if (recursive) Integer.MAX_VALUE else 0, filter).asScala
    def ls(path: Path, recursive: Boolean, filter: EntryFilter[_ >: T]): Seq[Entry[T]] =
      d.list(path, if (recursive) Integer.MAX_VALUE else 0, filter).asScala
  }
  val tests = Tests {
    'list - {
      "empty" - withTempDirectorySync { dir =>
        assert(Repositories.of(dir).ls(recursive = true, AllPass).isEmpty)
      }
      "files" - {
        'parent - {
          withTempFileSync { file =>
            val parent = file.getParent
            Repositories.of(parent).ls(recursive = true, AllPass) === Seq(file)
          }
        }
        'directly - {
          withTempFileSync { file =>
            val parent = file.getParent
            Repositories.of(parent).ls(file, recursive = true, AllPass) === Seq(file)
          }
        }
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: Path): EntryFilter[Path] =
              EntryFilters.fromFileFilter((_: File).toPath.getParent == dir)
            val directory = Repositories.of(dir)
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
                Repositories.of(dir).ls(recursive = false, AllPass) === Set(f, subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                Repositories.of(dir).ls(recursive = true, AllPass) === Set(f, f2, subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            Repositories.of(dir).ls(subdir, recursive = true, AllPass) === Seq(f)
            assert(
              Repositories
                .of(dir)
                .ls(Paths.get(s"$subdir.1"), recursive = true, AllPass)
                .isEmpty)
          }
        }
      }
      "filter" - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectorySync(dir) { subdir =>
            Repositories
              .of(dir)
              .ls(recursive = true, EntryFilters.fromFileFilter(!(_: File).isDirectory)) === Seq(f)
            Repositories
              .of(dir)
              .ls(recursive = true, EntryFilters.fromFileFilter((_: File).isDirectory)) === Seq(
              subdir)
          }
        }
      }
    }
    'recursive - withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        withTempFileSync(subdir) { f =>
          assert(f.exists)
          Repositories.of(subdir).ls(recursive = true, AllPass) === Seq(f)
          Repositories.of(dir, false).ls(recursive = true, AllPass) === Seq(subdir)
          Repositories.of(dir).ls(recursive = true, AllPass) === Set(subdir, f)
          Repositories.of(dir).ls(recursive = false, AllPass) === Seq(subdir)
        }
      }
    }
    'depth - {
      'nonnegative - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { file =>
            Repositories.of(dir, 0).ls(recursive = true, AllPass) === Set(subdir)
            Repositories.of(dir, 1).ls(recursive = true, AllPass) === Set(subdir, file)
          }
        }
      }
      'negative - {
        'file - withTempFileSync { file =>
          Repositories.of(file, -1).ls(recursive = true, AllPass) === Seq(file)
        }
        'directory - withTempDirectorySync { dir =>
          Repositories.of(dir, -1).ls(recursive = true, AllPass) === Seq(dir)
        }
        'parameter - withTempFileSync { file =>
          val dir = file.getParent
          val directory = Repositories.of(dir, Integer.MAX_VALUE)
          directory.list(dir, -1, AllPass).asScala === Seq(dir)
          directory.list(dir, 0, AllPass).asScala === Seq(file)
        }
      }
    }
    'converter - {
      'exceptions - {
        'directory - withTempFileSync { file =>
          val parent = file.getParent
          val dir = Repositories.cached(parent, (p: Path) => {
            if (Files.isDirectory(p)) throw new IOException("die")
            1: Integer
          })
          dir.entry().getValue.getOrElse(2) ==> 2
          dir.entry().getValue.left().getValue.getMessage ==> "die"
          dir.ls(recursive = true, AllPass) === Seq(file)
        }
        'subdirectory - withTempDirectorySync { dir =>
          val subdir = Files.createDirectory(dir.resolve("subdir"))
          val directory = Repositories.cached(dir, (p: Path) => {
            if (p.toString.contains("subdir")) throw new IOException("die")
            1: Integer
          }, 0)
          directory.entry().getValue.getOrElse(2) ==> 1
          directory
            .ls(recursive = true, AllPass)
            .map(e => e.getPath -> e.getValue.getOrElse(3)) === Seq(subdir -> 3)
        }
        'file - withTempFileSync { file =>
          val parent = file.getParent
          val dir = Repositories.cached(parent, (p: Path) => {
            if (!Files.isDirectory(p)) throw new IOException("die")
            1: Integer
          })
          dir.entry().getValue.getOrElse(2) ==> 1
          dir
            .ls(recursive = true, AllPass)
            .map(e => e.getPath -> e.getValue.getOrElse(3)) === Seq(file -> 3)
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
                val directory = Repositories.of(dir)
                directory.ls(recursive = true, AllPass) === Seq(subdir)
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
        Repositories.of(parent).ls(recursive = true, AllPass) === Set(file, link)
      }
      'directory - withTempDirectory { dir =>
        withTempDirectorySync { otherDir =>
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          val file = otherDir.resolve("file").createFile()
          val dirFile = dir.resolve("link").resolve("file")
          Repositories.of(dir).ls(recursive = true, AllPass) === Set(link, dirFile)
        }
      }
      'loop - {
        'initial - withTempDirectory { dir =>
          withTempDirectorySync { otherDir =>
            val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
            val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
            Repositories.of(dir).ls(recursive = true, AllPass) === Set(
              dirToOtherDirLink,
              dirToOtherDirLink.resolve("dir"))
          }
        }
        'updated - {
          'indirect - {
            'remoteLink - withTempDirectory { dir =>
              withTempDirectorySync { otherDir =>
                val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
                val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
                val directory = Repositories.of(dir)
                directory.ls(recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                                dirToOtherDirLink.resolve("dir"))
                otherDirToDirLink.delete()
                Files.createDirectory(otherDirToDirLink)
                val nestedFile = otherDirToDirLink.resolve("file").createFile()
                val file = dirToOtherDirLink.resolve("dir").resolve("file")
                directory.update(dir, Entries.DIRECTORY)
                directory.ls(recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                                file.getParent,
                                                                file)
              }
            }
            'localLink - withTempDirectory { dir =>
              withTempDirectorySync { otherDir =>
                val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
                val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
                val directory = Repositories.of(dir)
                directory.ls(recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                                dirToOtherDirLink.resolve("dir"))
                dirToOtherDirLink.delete()
                Files.createDirectory(dirToOtherDirLink)
                val nestedFile = dirToOtherDirLink.resolve("file").createFile()
                directory.update(dir, Entries.DIRECTORY)
                directory.ls(recursive = true, AllPass) === Set(dirToOtherDirLink, nestedFile)
              }
            }
          }
          // This test is different from those above because it calls update with dirToOtherDirLink
          // rather than with dir
          'direct - withTempDirectory { dir =>
            withTempDirectorySync { otherDir =>
              val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
              val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
              val directory = Repositories.of(dir)
              directory.ls(recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                              dirToOtherDirLink.resolve("dir"))
              dirToOtherDirLink.delete()
              Files.createDirectory(dirToOtherDirLink)
              val nestedFile = dirToOtherDirLink.resolve("file").createFile()
              directory.update(dirToOtherDirLink, Entries.DIRECTORY)
              directory.ls(recursive = true, AllPass) === Set(dirToOtherDirLink, nestedFile)
            }
          }
        }
      }
    }
  }
}
