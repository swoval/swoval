package com.swoval.files

import java.io.{ File, FileFilter }

import com.swoval.files.FileWatchEvent.{ Create, Delete }
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
        assert(Directory.of(dir).list(recursive = true, (_: Path) => true).isEmpty)
      }
      "files" - withTempFileSync { file =>
        val parent = file.getParent
        Directory.of(parent).list(recursive = true, (_: Path) => true) === Seq(file)
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: Path): PathFilter = (f: Path) => f.getParent == dir
            val directory = Directory.of(dir)
            directory.list(recursive = true, parentEquals(dir)) === Seq(subdir)
            directory.list(recursive = true, parentEquals(subdir)) === Seq(f)
            directory.list(recursive = true, (_: Path) => true) === Seq(subdir, f)
          }
        }
      }
      "directories" - {
        "non-recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { (_: Path) =>
                Directory.of(dir).list(recursive = false, (_: Path) => true).toSet === Set(f,
                                                                                           subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                Directory.of(dir).list(recursive = true, (_: Path) => true).toSet === Set(f,
                                                                                          f2,
                                                                                          subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            Directory.of(dir).list(subdir, recursive = true, (_: Path) => true) === Seq(f)
            assert(
              Directory
                .of(dir)
                .list(Path(s"${subdir.fullName}.1"), recursive = true, (_: Path) => true)
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
          var event: Option[FileWatchEvent] = None
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
              .flatMap((_: Either[Path, Directory]).toOption)
              .map(_.list(recursive = true, (_: Path) => true)) === Some(Seq(f))
            directory.find(f) === Some(Left(f))
          }
        }
      }
    }
    'add - {
      'file - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempFileSync(dir) { f =>
          directory.list(f, recursive = false, (_: Path) => true) === Seq.empty
          assert(directory.add(f, isFile = true, (_: FileWatchEvent) => {}))
          directory.list(f, recursive = false, (_: Path) => true) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.list(f, recursive = true, (_: Path) => true) === Seq.empty
            assert(directory.add(subdir, isFile = false, (_: FileWatchEvent) => {}))
            directory.list(dir, recursive = true, (_: Path) => true) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.add(subdir, isFile = false, (_: FileWatchEvent) => {}))
          withTempFileSync(subdir) { f =>
            assert(directory.add(f, isFile = true, (_: FileWatchEvent) => {}))
            directory.list(recursive = true, (_: Path) => true).toSet === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.add(f, isFile = true, (_: FileWatchEvent) => {}))
            directory.list(recursive = true, (_: Path) => true).toSet === Set(f, subdir)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = Directory.of(dir)
          directory.list(recursive = false, (_: Path) => true) === Seq(f)
          assert(directory.remove(f))
          assert(directory.list(recursive = false, (_: Path) => true).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = Directory.of(dir)
              def list = directory.list(recursive = true, (_: Path) => true).toSet
              list === Set(f, subdir, nestedSubdir)
              assert(directory.remove(f))
              list === Set(subdir, nestedSubdir)
            }
          }
        }
      }
    }
  }
}
