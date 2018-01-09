package com.swoval.files

import java.io.File

import com.swoval.files.FileWatchEvent.{ Create, Delete }
import com.swoval.files.test.{
  withTempDirectory,
  withTempDirectorySync,
  withTempFile,
  withTempFileSync
}
import com.swoval.test._
import sbt.io._
import utest._

object DirectoryTest extends TestSuite {
  implicit class RichFunction(f: File => Boolean) extends FileFilter {
    override def accept(file: File) = f(file)
  }
  val tests = Tests {
    'list - {
      "empty" - withTempDirectorySync { dir =>
        assert(Directory(dir).list(recursive = true, _ => true).isEmpty)
      }
      "files" - withTempFileSync { file =>
        val parent = file.getParent
        Directory(parent).list(recursive = true, _ => true) === Seq(file)
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: Path): PathFilter = (f: Path) => f.getParent == dir
            val directory = Directory(dir)
            directory.list(recursive = true, parentEquals(dir)) === Seq(subdir)
            directory.list(recursive = true, parentEquals(subdir)) === Seq(f)
            directory.list(recursive = true, _ => true) === Seq(subdir, f)
          }
        }
      }
      "directories" - {
        "non-recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { _ =>
                Directory(dir).list(recursive = false, _ => true).toSet === Set(f, subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                Directory(dir).list(recursive = true, _ => true).toSet === Set(f, f2, subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            Directory(dir).list(subdir, recursive = true, _ => true) === Seq(f)
            assert(
              Directory(dir)
                .list(Path(s"${subdir.fullName}.1"), recursive = true, _ => true)
                .isEmpty)
          }
        }
      }
      "filter" - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectorySync(dir) { subdir =>
            Directory(dir).list(recursive = true, !_.isDirectory) === Seq(f)
            Directory(dir).list(recursive = true, _.isDirectory) === Seq(subdir)
          }
        }
      }
    }
    'traverse - {
      'callback - withTempDirectory { dir =>
        val directory = Directory(dir)
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
          val directory = Directory(dir)
          directory.find(f) === Some(Left(f))
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            val directory = Directory(dir)
            directory
              .find(subdir)
              .flatMap(_.toOption)
              .map(_.list(recursive = true, _ => true)) === Some(Seq(f))
            directory.find(f) === Some(Left(f))
          }
        }
      }
    }
    'add - {
      'file - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempFileSync(dir) { f =>
          directory.list(f, recursive = false, _ => true) === Seq.empty
          assert(directory.add(f, isFile = true, _ => {}))
          directory.list(f, recursive = false, _ => true) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.list(f, recursive = true, _ => true) === Seq.empty
            assert(directory.add(subdir, isFile = false, _ => {}))
            directory.list(dir, recursive = true, _ => true) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.add(subdir, isFile = false, _ => {}))
          withTempFileSync(subdir) { f =>
            assert(directory.add(f, isFile = true, _ => {}))
            directory.list(recursive = true, _ => true).toSet === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.add(f, isFile = true, _ => {}))
            directory.list(recursive = true, _ => true).toSet === Set(f, subdir)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = Directory(dir)
          directory.list(recursive = false, _ => true) === Seq(f)
          assert(directory.remove(f))
          assert(directory.list(recursive = false, _ => true).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = Directory(dir)
              def list = directory.list(recursive = true, _ => true).toSet
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
