package com.swoval.files

import java.io.{ File, FileFilter }

import com.swoval.files.Directory.PathConverter
import com.swoval.files.FileWatchEvent.{ Create, Delete }
import com.swoval.files.Path.DelegatePath
import com.swoval.files.PathFilter.AllPass
import com.swoval.files.compat._
import com.swoval.files.test.{
  withTempDirectory,
  withTempDirectorySync,
  withTempFile,
  withTempFileSync
}
import com.swoval.test._
import utest._

object DirectoryTest extends TestSuite {
  implicit class RichFunction(f: File => Boolean) extends FileFilter {
    override def accept(file: File) = f(file)
  }
  val tests = Tests {
    'list - {
      "empty" - withTempDirectorySync { dir =>
        assert(Directory.of(dir).list(recursive = true, AllPass).isEmpty)
      }
      "files" - withTempFileSync { file =>
        val parent = file.getParent
        Directory.of(parent).list(recursive = true, AllPass) === Seq(file)
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: Path): PathFilter = (f: Path) => f.getParent == dir
            val directory = Directory.of(dir)
            directory.list(recursive = true, parentEquals(dir)) === Seq(subdir)
            directory.list(recursive = true, parentEquals(subdir)) === Seq(f)
            directory.list(recursive = true, AllPass) === Seq(subdir, f)
          }
        }
      }
      "directories" - {
        "non-recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { (_: Path) =>
                Directory.of(dir).list(recursive = false, AllPass).toSet === Set(f, subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                Directory.of(dir).list(recursive = true, AllPass).toSet === Set(f, f2, subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            Directory.of(dir).list(subdir, recursive = true, AllPass) === Seq(f)
            assert(
              Directory
                .of(dir)
                .list(Path(s"${subdir.fullName}.1"), recursive = true, AllPass)
                .isEmpty)
          }
        }
      }
      "filter" - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectorySync(dir) { subdir =>
            Directory.of(dir).list(recursive = true, !(_: Path).isDirectory) === Seq(f)
            Directory.of(dir).list(recursive = true, (_: Path).isDirectory) === Seq(subdir)
          }
        }
      }
    }
    'traverse - {
      'callback - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempFileSync(dir) { f =>
          var event: Option[FileWatchEvent[Path]] = None
          directory.traverse(e => event = Some(e))
          event ==> Some(FileWatchEvent(f, Create))
          f.delete()
          directory.traverse(e => event = Some(e))
          event === Some(FileWatchEvent(f, Delete))
        }
      }
    }
    'find - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = Directory.of(dir)
          directory.find(f) === Some(Left(f))
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            val directory = Directory.of(dir)
            directory
              .find(subdir)
              .flatMap((_: Either[Path, Directory[Path]]).toOption)
              .map(_.list(recursive = true, AllPass)) === Some(Seq(f))
            directory.find(f) === Some(Left(f))
          }
        }
      }
    }
    'add - {
      'file - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempFileSync(dir) { f =>
          directory.list(f, recursive = false, AllPass) === Seq.empty
          assert(directory.add(f, isFile = true, FileWatchEvent.Ignore))
          directory.list(f, recursive = false, AllPass) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.list(f, recursive = true, AllPass) === Seq.empty
            assert(directory.add(subdir, isFile = false, FileWatchEvent.Ignore))
            directory.list(dir, recursive = true, AllPass) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.add(subdir, isFile = false, FileWatchEvent.Ignore))
          withTempFileSync(subdir) { f =>
            assert(directory.add(f, isFile = true, FileWatchEvent.Ignore))
            directory.list(recursive = true, AllPass).toSet === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.add(f, isFile = true, FileWatchEvent.Ignore))
            directory.list(recursive = true, AllPass).toSet === Set(f, subdir)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = Directory.of(dir)
          directory.list(recursive = false, AllPass) === Seq(f)
          assert(directory.remove(f))
          assert(directory.list(recursive = false, AllPass).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = Directory.of(dir)
              def list = directory.list(recursive = true, AllPass).toSet
              list === Set(f, subdir, nestedSubdir)
              assert(directory.remove(f))
              list === Set(subdir, nestedSubdir)
            }
          }
        }
      }
    }
    'subTypes - {
      withTempFileSync { f =>
        val dir = Directory.of[CachedLastModified](f.getParent)
        val lastModified = f.lastModified
        val updatedLastModified = 2000
        f.setLastModifiedTime(updatedLastModified)
        f.lastModified ==> updatedLastModified
        val cachedFile = dir.list(f, recursive = true, AllPass).head
        cachedFile.lastModified ==> lastModified
      }
    }
  }
}
