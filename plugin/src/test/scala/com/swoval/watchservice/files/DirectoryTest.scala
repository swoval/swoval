package com.swoval.watchservice.files

import java.io.File
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE }

import com.swoval.test._
import com.swoval.watchservice.files.Directory.FileEvent
import sbt.io.{ AllPassFilter, FileFilter }
import utest._

object DirectoryTest extends TestSuite {
  implicit class RichFunction(f: File => Boolean) extends FileFilter {
    override def accept(file: File) = f(file)
  }
  val tests = Tests {
    'list - {
      "empty" - withTempDirectory { dir =>
        assert(Directory(dir).list(recursive = true, AllPassFilter).isEmpty)
      }
      "files" - withTempFile { file =>
        val parent = file.getParent
        Directory(parent).list(recursive = true, AllPassFilter) === Seq(file.toFile)
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFile(subdir) { f =>
            def parentEquals(dir: Path) = (f: File) => f.toPath.toAbsolutePath.getParent == dir
            val directory = Directory(dir)
            directory.list(recursive = true, parentEquals(dir)) === Seq(subdir.toFile)
            directory.list(recursive = true, parentEquals(subdir)) === Seq(f.toFile)
            directory.list(recursive = true, AllPassFilter) === Seq(subdir.toFile, f.toFile)
          }
        }
      }
      "directories" - {
        "non-recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFile(subdir) { _ =>
                Directory(dir).list(recursive = false, AllPassFilter).toSet ===
                  Set(f, subdir).map(_.toFile)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFile(subdir) { f2 =>
                Directory(dir).list(recursive = true, AllPassFilter).toSet === Set(f, f2, subdir)
                  .map(_.toFile)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFile(subdir) { f =>
            Directory(dir).list(subdir, recursive = true, AllPassFilter) === Seq(f.toFile)
            assert(
              Directory(dir)
                .list(new File(s"$subdir.1").toPath, recursive = true, AllPassFilter)
                .isEmpty)
          }
        }
      }
      "filter" - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectory(dir) { subdir =>
            Directory(dir).list(recursive = true, (_: File).isFile) === Seq(f.toFile)
            Directory(dir).list(recursive = true, (_: File).isDirectory) === Seq(subdir.toFile)
          }
        }
      }
    }
    'traverse - {
      'callback - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempFile(dir) { f =>
          var event: Option[FileEvent[_]] = None
          directory.traverse(e => event = Some(e))
          event ==> Some(FileEvent(f, ENTRY_CREATE))
          f.toFile.delete()
          directory.traverse(e => event = Some(e))
          event ==> Some(FileEvent(f, ENTRY_DELETE))
        }
      }
    }
    'find - {
      'direct - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          val directory = Directory(dir)
          directory.find(f) === Some(Left(f.toFile))
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFile(subdir) { f =>
            val directory = Directory(dir)
            directory
              .find(subdir)
              .flatMap(_.toOption)
              .map(_.list(recursive = true, _ => true)) === Some(Seq(f.toFile))
            directory.find(f) === Some(Left(f.toFile))
          }
        }
      }
    }
    'add - {
      'direct - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempFile(dir) { f =>
          directory.list(f, recursive = false, AllPassFilter) === Seq.empty
          assert(directory.add(f, isFile = true, _ => {}))
          directory.list(f, recursive = false, AllPassFilter) === Seq(f.toFile)
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.add(subdir, isFile = false, _ => {}))
          withTempFile(subdir) { f =>
            assert(directory.add(f, isFile = true, _ => {}))
            directory.list(recursive = true, AllPassFilter).toSet === Set(subdir.toFile, f.toFile)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Directory(dir)
        withTempDirectory(dir) { subdir =>
          withTempFile(subdir) { f =>
            assert(directory.add(f, isFile = true, _ => {}))
            directory.list(recursive = true, AllPassFilter).toSet === Set(f, subdir).map(_.toFile)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          val directory = Directory(dir)
          directory.list(recursive = false, AllPassFilter) === Seq(f.toFile)
          assert(directory.remove(f))
          assert(directory.list(recursive = false, AllPassFilter).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFile(nestedSubdir) { f =>
              val directory = Directory(dir)
              def list = directory.list(recursive = true, AllPassFilter).toSet
              list === Set(f, subdir, nestedSubdir).map(_.toFile)
              assert(directory.remove(f))
              list === Set(subdir, nestedSubdir).map(_.toFile)
            }
          }
        }
      }
    }
  }
}
