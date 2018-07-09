package com.swoval.files

import java.io.IOException
import java.nio.file.{ Files, Path }

import com.swoval.files.Directory.{ Entry, EntryFilter }
import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.EntryOps._
import com.swoval.files.Repositories.{ cached, of }
import com.swoval.files.RepositoryTest.RepositoryOps
import com.swoval.files.test._
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._

object CachedRepositoryTest extends TestSuite {
  class Updates[T](u: Directory.Updates[T]) {
    private[this] var _creations: Seq[Directory.Entry[T]] = Nil
    private[this] var _deletions: Seq[Directory.Entry[T]] = Nil
    private[this] var _updates: Seq[(Directory.Entry[T], Entry[T])] = Nil
    u.observe(new Directory.Observer[T] {
      override def onCreate(newEntry: Directory.Entry[T]): Unit = _creations :+= newEntry
      override def onDelete(oldEntry: Directory.Entry[T]): Unit = _deletions :+= oldEntry
      override def onUpdate(oldEntry: Directory.Entry[T], newEntry: Directory.Entry[T]): Unit =
        _updates :+= (oldEntry, newEntry)
      override def onError(path: Path, exception: IOException): Unit = {}
    })
    def creations: Seq[Directory.Entry[T]] = _creations
    def deletions: Seq[Directory.Entry[T]] = _deletions
    def updates: Seq[(Directory.Entry[T], Directory.Entry[T])] = _updates
  }
  implicit class UpdateOps[T](val u: Directory.Updates[T]) extends AnyVal {
    def toUpdates: CachedRepositoryTest.Updates[T] = new CachedRepositoryTest.Updates(u)
  }

  val tests = Tests {
    'add - {
      'file - withTempDirectory { dir =>
        val directory = Repositories.of(dir)
        withTempFileSync(dir) { f =>
          directory.ls(f, recursive = false, AllPass) === Seq.empty
          assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
          directory.ls(f, recursive = false, AllPass) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = Repositories.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.ls(f, recursive = true, AllPass) === Seq.empty
            assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
            directory.ls(dir, recursive = true, AllPass) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Repositories.of(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.update(subdir, Entries.DIRECTORY).toUpdates.creations.nonEmpty)
          withTempFileSync(subdir) { f =>
            assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
            directory.ls(recursive = true, AllPass) === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Repositories.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
            directory.ls(recursive = true, AllPass) === Set(f, subdir)
          }
        }
      }
      'overlapping - {
        'base - withTempDirectory { dir =>
          val directory = Repositories.of(dir, 0)
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { file =>
              directory.update(subdir, Entries.DIRECTORY)
              directory.ls(recursive = true, AllPass) === Set(subdir)
            }
          }
        }
        'nested - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val directory = Repositories.of(dir, 2)
            withTempDirectory(subdir) { nestedSubdir =>
              withTempDirectory(nestedSubdir) { deepNestedSubdir =>
                withTempFileSync(deepNestedSubdir) { file =>
                  directory.update(nestedSubdir, Entries.DIRECTORY)
                  directory.ls(recursive = true, AllPass) === Set(subdir,
                                                                  nestedSubdir,
                                                                  deepNestedSubdir)
                }
              }
            }
          }
        }
      }
    }
    'update - {
      'directory - {
        'simple - withTempDirectory { dir =>
          withTempDirectorySync(dir) { subdir =>
            val directory = Repositories.of(dir)
            val file = subdir.resolve("file").createFile()
            val updates = directory.update(subdir, Entries.DIRECTORY).toUpdates
            val entry: Entry[Path] =
              Entries.get(subdir, Entries.DIRECTORY, identity(_: Path), subdir)
            updates.updates === Seq(entry -> entry)
            updates.creations === Seq(file)
          }
        }
        'nested - {
          'created - withTempDirectory { dir =>
            withTempDirectorySync(dir) { subdir =>
              val directory = Repositories.of(dir)
              val nestedSubdir = Files.createDirectory(subdir.resolve("nested"))
              val nestedFile = nestedSubdir.resolve("file").createFile()
              val updates = directory.update(subdir, Entries.DIRECTORY).toUpdates
              val entry: Entry[Path] =
                Entries.get(subdir, Entries.DIRECTORY, identity(_: Path), subdir)
              updates.updates === Seq(entry -> entry)
              updates.creations === Set(nestedSubdir, nestedFile)
            }
          }
          'removed - withTempDirectory { dir =>
            withTempDirectorySync(dir) { subdir =>
              val nestedSubdir = Files.createDirectory(subdir.resolve("nested"))
              val nestedFile = nestedSubdir.resolve("file").createFile()
              val directory = Repositories.of(dir)
              nestedSubdir.deleteRecursive()
              val updates = directory.update(subdir, Entries.DIRECTORY).toUpdates
              val entry: Entry[Path] =
                Entries.get(subdir, Entries.DIRECTORY, identity(_: Path), subdir)
              updates.updates === Seq(entry -> entry)
              updates.deletions === Set(nestedSubdir, nestedFile)
            }
          }
        }
      }
      'depth - withTempDirectory { dir =>
        val directory = Repositories.of(dir, 0)
        withTempDirectory(dir) { subdir =>
          withTempDirectorySync(subdir) { nestedSubdir =>
            directory.ls(recursive = true, AllPass) === Seq.empty[Path]
            directory.update(subdir, Entries.DIRECTORY)
            directory.ls(recursive = true, AllPass) === Seq(subdir)
            directory.update(nestedSubdir, Entries.DIRECTORY)
            directory.ls(recursive = true, AllPass) === Seq(subdir)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = Repositories.of(dir)
          directory.ls(recursive = false, AllPass) === Seq(f)
          assert(Option(directory.remove(f)).isDefined)
          assert(directory.ls(recursive = false, AllPass).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = Repositories.of(dir)
              def ls = directory.ls(recursive = true, AllPass)
              ls === Set(f, subdir, nestedSubdir)
              directory.remove(f).asScala === Seq(f)
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
            Repositories.cached[LastModified](f.getParent, LastModified(_: Path), true)
          val lastModified = f.lastModified
          val updatedLastModified = 2000
          f.setLastModifiedTime(updatedLastModified)
          f.lastModified ==> updatedLastModified
          val cachedFile = dir.ls(f, recursive = true, AllPass).head
          cachedFile.value.lastModified ==> lastModified
        }
      }
      'newFields - withTempFileSync { f =>
        f.write("foo")
        val initialBytes = "foo".getBytes.toIndexedSeq
        val dir = Repositories.cached[FileBytes](f.getParent, FileBytes(_: Path), true)
        def filter(bytes: Seq[Byte]): EntryFilter[FileBytes] =
          new EntryFilter[FileBytes] {
            override def accept(p: Entry[_ <: FileBytes]): Boolean = p.value.bytes == bytes
          }
        val cachedFile =
          dir.ls(f, recursive = true, filter(initialBytes)).head
        cachedFile.value.bytes ==> initialBytes
        f.write("bar")
        val newBytes = "bar".getBytes
        cachedFile.value.bytes ==> initialBytes
        f.getBytes ==> newBytes
        dir.update(f, Entries.FILE)
        val newCachedFile = dir.ls(f, recursive = true, filter(newBytes)).head
        newCachedFile.value.bytes.toSeq ==> newBytes.toSeq
        dir.ls(f, recursive = true, filter(initialBytes)) === Seq.empty[Path]
      }
    }
  }

}
