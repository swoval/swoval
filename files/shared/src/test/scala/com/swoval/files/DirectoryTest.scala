package com.swoval.files

import java.io.File
import java.nio.file.{ Path => JPath }

import com.swoval.files.Directory.Entry
import com.swoval.files.test._
import utest._

import scala.collection.JavaConverters._

object DirectoryTest extends TestSuite {
  implicit class DirectoryOps[T](val d: Directory[T]) {
    def ls(recursive: Boolean, filter: Directory.EntryFilter[_ >: T]): Seq[Entry[T]] =
      d.list(recursive, filter).asScala
    def ls(path: JPath, recursive: Boolean, filter: Directory.EntryFilter[_ >: T]): Seq[Entry[T]] =
      d.list(path, recursive, filter).asScala
  }

  val tests = Tests {
    'list - {
      "empty" - withTempDirectorySync { dir =>
        assert(Directory.of(dir).ls(recursive = true, EntryFilters.AllPass).isEmpty)
      }
      "files" - {
        'parent - {
          withTempFileSync { file =>
            val parent = file.getParent
            Directory.of(parent).ls(recursive = true, EntryFilters.AllPass) === Seq(file)
          }
        }
        'directly - {
          withTempFileSync { file =>
            val parent = file.getParent
            Directory.of(parent).ls(file, recursive = true, EntryFilters.AllPass) === Seq(file)
          }
        }
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: JPath): Directory.EntryFilter[JPath] =
              EntryFilters.fromFileFilter((_: File).toPath.getParent == dir)
            val directory = Directory.of(dir)
            directory.ls(recursive = true, parentEquals(dir)) === Seq(subdir)
            directory.ls(recursive = true, parentEquals(subdir)) === Seq(f)
            directory.ls(recursive = true, EntryFilters.AllPass) === Seq(subdir, f)
          }
        }
      }
      "directories" - {
        "non-recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { _ =>
                Directory.of(dir).ls(recursive = false, EntryFilters.AllPass) === Set(f, subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                Directory.of(dir).ls(recursive = true, EntryFilters.AllPass) === Set(f, f2, subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            Directory.of(dir).ls(subdir, recursive = true, EntryFilters.AllPass) === Seq(f)
            assert(
              Directory
                .of(dir)
                .ls(Path(s"$subdir.1"), recursive = true, EntryFilters.AllPass)
                .isEmpty)
          }
        }
      }
      "filter" - withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectorySync(dir) { subdir =>
            Directory
              .of(dir)
              .ls(recursive = true, EntryFilters.fromFileFilter(!(_: File).isDirectory)) === Seq(f)
            Directory
              .of(dir)
              .ls(recursive = true, EntryFilters.fromFileFilter((_: File).isDirectory)) === Seq(
              subdir)
          }
        }
      }
    }
    'add - {
      'file - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempFileSync(dir) { f =>
          directory.ls(f, recursive = false, EntryFilters.AllPass) === Seq.empty
          assert(directory.addUpdate(f, true).asScala.nonEmpty)
          directory.ls(f, recursive = false, EntryFilters.AllPass) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.ls(f, recursive = true, EntryFilters.AllPass) === Seq.empty
            assert(directory.addUpdate(f, false).asScala.nonEmpty)
            directory.ls(dir, recursive = true, EntryFilters.AllPass) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.addUpdate(subdir, false).asScala.nonEmpty)
          withTempFileSync(subdir) { f =>
            assert(directory.addUpdate(f, true).asScala.nonEmpty)
            directory.ls(recursive = true, EntryFilters.AllPass) === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.addUpdate(f, true).asScala.nonEmpty)
            directory.ls(recursive = true, EntryFilters.AllPass) === Set(f, subdir)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = Directory.of(dir)
          directory.ls(recursive = false, EntryFilters.AllPass) === Seq(f)
          assert(Option(directory.remove(f)).isDefined)
          assert(directory.ls(recursive = false, EntryFilters.AllPass).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = Directory.of(dir)
              def ls = directory.ls(recursive = true, EntryFilters.AllPass)
              ls === Set(f, subdir, nestedSubdir)
              assert(Option(directory.remove(f)).isDefined)
              ls === Set(subdir, nestedSubdir)
            }
          }
        }
      }
    }
    'subTypes - {
      'overrides - {
        withTempFileSync { f =>
          val dir =
            Directory.cached[LastModified](f.getParent, LastModified(_: JPath), true)
          val lastModified = f.lastModified
          val updatedLastModified = 2000
          f.setLastModifiedTime(updatedLastModified)
          f.lastModified ==> updatedLastModified
          val cachedFile = dir.ls(f, recursive = true, EntryFilters.AllPass).head
          cachedFile.value.lastModified ==> lastModified
        }
      }
      'newFields - withTempFileSync { f =>
        f.write("foo")
        val initialBytes = "foo".getBytes.toIndexedSeq
        val dir = Directory.cached[FileBytes](f.getParent, FileBytes(_: JPath), true)
        def filter(bytes: Seq[Byte]): Directory.EntryFilter[FileBytes] =
          new Directory.EntryFilter[FileBytes] {
            override def accept(p: Entry[_ <: FileBytes]): Boolean = p.value.bytes == bytes
          }
        val cachedFile =
          dir.ls(f, recursive = true, filter(initialBytes)).head
        cachedFile.value.bytes ==> initialBytes
        f.write("bar")
        val newBytes = "bar".getBytes
        cachedFile.value.bytes ==> initialBytes
        f.getBytes ==> newBytes
        dir.addUpdate(f, true)
        val newCachedFile = dir.ls(f, recursive = true, filter(newBytes)).head
        assert(newCachedFile.value.bytes.sameElements(newBytes))
        dir.ls(f, recursive = true, filter(initialBytes)) === Seq.empty[JPath]
      }
    }
    'recursive - withTempDirectory { dir =>
      withTempDirectorySync(dir) { subdir =>
        withTempFileSync(subdir) { f =>
          assert(f.exists)
          Directory.of(subdir).ls(recursive = true, EntryFilters.AllPass) === Seq(f)
          Directory.of(dir, false).ls(recursive = true, EntryFilters.AllPass) === Seq(subdir)
          Directory.of(dir).ls(recursive = true, EntryFilters.AllPass) === Set(subdir, f)
          Directory.of(dir).ls(recursive = false, EntryFilters.AllPass) === Seq(subdir)
        }
      }
    }
  }
}
