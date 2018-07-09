package com.swoval.files

import java.io.{ File, IOException }
import java.nio.file.{ Files, Path, Paths }

import com.swoval.files.Directory.{ Entry, EntryFilter }
import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.EntryOps._
import com.swoval.files.test._
import com.swoval.runtime.Platform
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._

object DirectoryTest extends TestSuite {
  implicit class DirectoryOps[T <: AnyRef](val d: Directory[T]) {
    def ls(recursive: Boolean, filter: EntryFilter[_ >: T]): Seq[Entry[T]] =
      d.list(recursive, filter).asScala
    def ls(path: Path, recursive: Boolean, filter: EntryFilter[_ >: T]): Seq[Entry[T]] =
      d.list(path, recursive, filter).asScala
  }
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
    def toUpdates: DirectoryTest.Updates[T] = new DirectoryTest.Updates(u)
  }

  val tests = Tests {
    'list - {
      "empty" - withTempDirectorySync { dir =>
        assert(Directory.of(dir).ls(recursive = true, AllPass).isEmpty)
      }
      "files" - {
        'parent - {
          withTempFileSync { file =>
            val parent = file.getParent
            Directory.of(parent).ls(recursive = true, AllPass) === Seq(file)
          }
        }
        'directly - {
          withTempFileSync { file =>
            val parent = file.getParent
            Directory.of(parent).ls(file, recursive = true, AllPass) === Seq(file)
          }
        }
      }
      "resolution" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            def parentEquals(dir: Path): EntryFilter[Path] =
              EntryFilters.fromFileFilter((_: File).toPath.getParent == dir)
            val directory = Directory.of(dir)
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
                Directory.of(dir).ls(recursive = false, AllPass) === Set(f, subdir)
              }
            }
          }
        }
        "recursive" - withTempDirectory { dir =>
          withTempFile(dir) { f =>
            withTempDirectory(dir) { subdir =>
              withTempFileSync(subdir) { f2 =>
                Directory.of(dir).ls(recursive = true, AllPass) === Set(f, f2, subdir)
              }
            }
          }
        }
      }
      "subdirectories" - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            Directory.of(dir).ls(subdir, recursive = true, AllPass) === Seq(f)
            assert(
              Directory
                .of(dir)
                .ls(Paths.get(s"$subdir.1"), recursive = true, AllPass)
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
          directory.ls(f, recursive = false, AllPass) === Seq.empty
          assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
          directory.ls(f, recursive = false, AllPass) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.ls(f, recursive = true, AllPass) === Seq.empty
            assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
            directory.ls(dir, recursive = true, AllPass) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          assert(directory.update(subdir, Entries.DIRECTORY).toUpdates.creations.nonEmpty)
          withTempFileSync(subdir) { f =>
            assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
            directory.ls(recursive = true, AllPass) === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = Directory.of(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.update(f, Entries.FILE).toUpdates.creations.nonEmpty)
            directory.ls(recursive = true, AllPass) === Set(f, subdir)
          }
        }
      }
      'overlapping - {
        'base - withTempDirectory { dir =>
          val directory = Directory.of(dir, 0)
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { file =>
              directory.update(subdir, Entries.DIRECTORY)
              directory.ls(recursive = true, AllPass) === Set(subdir)
            }
          }
        }
        'nested - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val directory = Directory.of(dir, 2)
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
            val directory = Directory.of(dir)
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
              val directory = Directory.of(dir)
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
              val directory = Directory.of(dir)
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
        val directory = Directory.of(dir, 0)
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
          val directory = Directory.of(dir)
          directory.ls(recursive = false, AllPass) === Seq(f)
          assert(Option(directory.remove(f)).isDefined)
          assert(directory.ls(recursive = false, AllPass).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = Directory.of(dir)
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
            Directory.cached[LastModified](f.getParent, LastModified(_: Path), true)
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
        val dir = Directory.cached[FileBytes](f.getParent, FileBytes(_: Path), true)
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
    'recursive - withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        withTempFileSync(subdir) { f =>
          assert(f.exists)
          Directory.of(subdir).ls(recursive = true, AllPass) === Seq(f)
          Directory.of(dir, false).ls(recursive = true, AllPass) === Seq(subdir)
          Directory.of(dir).ls(recursive = true, AllPass) === Set(subdir, f)
          Directory.of(dir).ls(recursive = false, AllPass) === Seq(subdir)
        }
      }
    }
    'depth - {
      'nonnegative - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { file =>
            Directory.of(dir, 0).ls(recursive = true, AllPass) === Set(subdir)
            Directory.of(dir, 1).ls(recursive = true, AllPass) === Set(subdir, file)
          }
        }
      }
      'negative - {
        'file - withTempFileSync { file =>
          Directory.of(file, -1).ls(recursive = true, AllPass) === Seq(file)
        }
        'directory - withTempDirectorySync { dir =>
          Directory.of(dir, -1).ls(recursive = true, AllPass) === Seq(dir)
        }
        'parameter - withTempFileSync { file =>
          val dir = file.getParent
          val directory = Directory.of(dir, Integer.MAX_VALUE)
          directory.list(dir, -1, AllPass).asScala === Seq(dir)
          directory.list(dir, 0, AllPass).asScala === Seq(file)
        }
      }
    }
    'converter - {
      'exceptions - {
        'directory - withTempFileSync { file =>
          val parent = file.getParent
          val dir = Directory.cached(parent, (p: Path) => {
            if (Files.isDirectory(p)) throw new IOException("die")
            1: Integer
          })
          dir.entry().getValue.getOrElse(2) ==> 2
          dir.entry().getValue.left().getValue.getMessage ==> "die"
          dir.ls(recursive = true, AllPass) === Seq(file)
        }
        'subdirectory - withTempDirectorySync { dir =>
          val subdir = Files.createDirectory(dir.resolve("subdir"))
          val directory = Directory.cached(dir, (p: Path) => {
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
          val dir = Directory.cached(parent, (p: Path) => {
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
                val directory = Directory.of(dir)
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
        Directory.of(parent).ls(recursive = true, AllPass) === Set(file, link)
      }
      'directory - withTempDirectory { dir =>
        withTempDirectorySync { otherDir =>
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          val file = otherDir.resolve("file").createFile()
          val dirFile = dir.resolve("link").resolve("file")
          Directory.of(dir).ls(recursive = true, AllPass) === Set(link, dirFile)
        }
      }
      'loop - {
        'initial - withTempDirectory { dir =>
          withTempDirectorySync { otherDir =>
            val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
            val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
            Directory.of(dir).ls(recursive = true, AllPass) === Set(
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
                val directory = Directory.of(dir)
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
                val directory = Directory.of(dir)
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
              val directory = Directory.of(dir)
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
