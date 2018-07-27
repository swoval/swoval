package com.swoval.files

import java.io.IOException
import java.nio.file.{ Files, Path }

import com.swoval.files.DataViews.Entry
import com.swoval.files.EntryOps._
import com.swoval.files.FileTreeViewTest.RepositoryOps
import com.swoval.files.FileTreeViews.{ CacheObserver, Observer }
import com.swoval.files.test._
import com.swoval.functional.{ Consumer, Filter }
import com.swoval.functional.Filters.AllPass
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._
import scala.collection.mutable

object CachedFileTreeViewTest extends TestSuite {
  def newCachedView(path: Path): CachedDirectory[Path] = newCachedView(path, Integer.MAX_VALUE)
  def newCachedView(path: Path, maxDepth: Int): CachedDirectory[Path] =
    newCachedView(path, maxDepth, followLinks = true)
  def newCachedView(path: Path, maxDepth: Int, followLinks: Boolean): CachedDirectory[Path] =
    new CachedDirectoryImpl(path,
                            path,
                            (_: TypedPath).getPath,
                            maxDepth,
                            AllPass,
                            FileTreeViews.getDefault(followLinks))
      .init()
  class Updates[T](u: FileTreeViews.Updates[T]) {
    private[this] var _creations: Seq[Entry[T]] = Nil
    private[this] var _deletions: Seq[Entry[T]] = Nil
    private[this] var _updates: Seq[(Entry[T], Entry[T])] = Nil
    u.observe(new CacheObserver[T] {
      override def onCreate(newEntry: Entry[T]): Unit = _creations :+= newEntry
      override def onDelete(oldEntry: Entry[T]): Unit = _deletions :+= oldEntry
      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit =
        _updates :+= (oldEntry, newEntry)
      override def onError(exception: IOException): Unit = {}
    })
    def creations: Seq[Entry[T]] = _creations
    def deletions: Seq[Entry[T]] = _deletions
    def updates: Seq[(Entry[T], Entry[T])] = _updates
  }
  implicit class UpdateOps[T](val u: FileTreeViews.Updates[T]) extends AnyVal {
    def toUpdates: CachedFileTreeViewTest.Updates[T] = new CachedFileTreeViewTest.Updates(u)
  }

  val executor = new Executor {
    override def run(threadConsumer: Consumer[Executor#Thread], priority: Int): Unit =
      threadConsumer.accept(getThread())
    override def getThread(): Executor#Thread = null
  }
  implicit class CachedDirectoryOps[T](val cd: CachedDirectory[T]) extends AnyVal {
    def remove(path: Path): java.util.List[Entry[T]] =
      executor.block((t: Executor#Thread) => cd.remove(path, t)).get()
    def update(path: TypedPath): FileTreeViews.Updates[T] =
      executor.block((t: Executor#Thread) => cd.update(path, t)).get()
  }

  val tests = Tests {
    'add - {
      'file - withTempDirectory { dir =>
        val directory = newCachedView(dir)
        withTempFileSync(dir) { f =>
          directory.ls(f, recursive = false, AllPass) === Seq.empty
          assert(directory.update(TypedPaths.get(f, Entries.FILE)).toUpdates.creations.nonEmpty)
          directory.ls(f, recursive = false, AllPass) === Seq(f)
        }
      }
      'directory - withTempDirectory { dir =>
        val directory = newCachedView(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            directory.ls(f, recursive = true, AllPass) === Seq.empty
            assert(directory.update(TypedPaths.get(f, Entries.FILE)).toUpdates.creations.nonEmpty)
            directory.ls(dir, recursive = true, AllPass) === Seq(subdir, f)
          }
        }
      }
      'sequentially - withTempDirectory { dir =>
        val directory = newCachedView(dir)
        withTempDirectory(dir) { subdir =>
          assert(
            directory
              .update(TypedPaths.get(subdir, Entries.DIRECTORY))
              .toUpdates
              .creations
              .nonEmpty)
          withTempFileSync(subdir) { f =>
            assert(directory.update(TypedPaths.get(f, Entries.FILE)).toUpdates.creations.nonEmpty)
            directory.ls(recursive = true, AllPass) === Set(subdir, f)
          }
        }
      }
      'recursive - withTempDirectory { dir =>
        val directory = newCachedView(dir)
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { f =>
            assert(directory.update(TypedPaths.get(f, Entries.FILE)).toUpdates.creations.nonEmpty)
            directory.ls(recursive = true, AllPass) === Set(f, subdir)
          }
        }
      }
      'overlapping - {
        'base - withTempDirectory { dir =>
          val directory = newCachedView(dir, 0)
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { file =>
              directory.update(TypedPaths.get(subdir, Entries.DIRECTORY))
              directory.ls(recursive = true, AllPass) === Set(subdir)
            }
          }
        }
        'nested - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val directory = newCachedView(dir, 2)
            withTempDirectory(subdir) { nestedSubdir =>
              withTempDirectory(nestedSubdir) { deepNestedSubdir =>
                withTempFileSync(deepNestedSubdir) { file =>
                  directory.update(TypedPaths.get(nestedSubdir, Entries.DIRECTORY))
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
            val directory = newCachedView(dir)
            val file = subdir.resolve("file").createFile()
            val updates = directory.update(TypedPaths.get(subdir, Entries.DIRECTORY)).toUpdates
            val typedPath = TypedPaths.get(subdir, Entries.DIRECTORY)
            val entry: Entry[Path] = Entries.get(typedPath, _.getPath, typedPath)
            updates.updates === Seq(entry -> entry)
            updates.creations === Seq(file)
          }
        }
        'nested - {
          'created - withTempDirectory { dir =>
            withTempDirectorySync(dir) { subdir =>
              val directory = newCachedView(dir)
              val nestedSubdir = Files.createDirectory(subdir.resolve("nested"))
              val nestedFile = nestedSubdir.resolve("file").createFile()
              val updates = directory.update(TypedPaths.get(subdir, Entries.DIRECTORY)).toUpdates
              val typedPath = TypedPaths.get(subdir, Entries.DIRECTORY)
              val entry: Entry[Path] = Entries.get(typedPath, _.getPath, typedPath)
              updates.updates === Seq(entry -> entry)
              updates.creations === Set(nestedSubdir, nestedFile)
            }
          }
          'removed - withTempDirectory { dir =>
            withTempDirectorySync(dir) { subdir =>
              val nestedSubdir = Files.createDirectory(subdir.resolve("nested"))
              val nestedFile = nestedSubdir.resolve("file").createFile()
              val directory = newCachedView(dir)
              nestedSubdir.deleteRecursive()
              val updates = directory.update(TypedPaths.get(subdir, Entries.DIRECTORY)).toUpdates
              val typedPath = TypedPaths.get(subdir, Entries.DIRECTORY)
              val entry: Entry[Path] =
                Entries.get(TypedPaths.get(subdir, Entries.DIRECTORY), _.getPath, typedPath)
              updates.updates === Seq(entry -> entry)
              updates.deletions === Set(nestedSubdir, nestedFile)
            }
          }
        }
        'subfiles - withTempDirectorySync { dir =>
          val directory = newCachedView(dir)
          directory.list(Integer.MAX_VALUE, AllPass).asScala.toSeq === Seq.empty[TypedPath]
          val subdir: Path = Files.createDirectories(dir.resolve("subdir").resolve("nested"))
          val files = 1 to 2 map (i => subdir.resolve(s"file-$i").createFile())
          val found = mutable.Set.empty[Path]
          val updates = directory.update(TypedPaths.get(files.last, Entries.FILE))
          updates.observe(CacheObservers.fromObserver(new Observer[Entry[Path]] {
            override def onError(t: Throwable): Unit = {}
            override def onNext(t: Entry[Path]): Unit = found.add(t.getPath())
          }))
          val expected = (files :+ subdir :+ subdir.getParent).toSet
          found.toSet === expected
          directory.update(TypedPaths.get(subdir.getParent, Entries.DIRECTORY))
          directory.list(Integer.MAX_VALUE, AllPass).asScala.toSeq.map(_.getPath) === expected
        }
      }
      'depth - withTempDirectory { dir =>
        val directory = newCachedView(dir, 0)
        withTempDirectory(dir) { subdir =>
          withTempDirectorySync(subdir) { nestedSubdir =>
            directory.ls(recursive = true, AllPass) === Seq.empty[Path]
            directory.update(TypedPaths.get(subdir, Entries.DIRECTORY))
            directory.ls(recursive = true, AllPass) === Seq(subdir)
            directory.update(TypedPaths.get(nestedSubdir, Entries.DIRECTORY))
            directory.ls(recursive = true, AllPass) === Seq(subdir)
          }
        }
      }
    }
    'remove - {
      'direct - withTempDirectory { dir =>
        withTempFileSync(dir) { f =>
          val directory = newCachedView(dir)
          directory.ls(recursive = false, AllPass) === Seq(f)
          assert(Option(directory.remove(f)).isDefined)
          assert(directory.ls(recursive = false, AllPass).isEmpty)
        }
      }
      'recursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { nestedSubdir =>
            withTempFileSync(nestedSubdir) { f =>
              val directory = newCachedView(dir)
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
            FileTreeViews.cached[LastModified](f.getParent,
                                               LastModified(_: TypedPath),
                                               Integer.MAX_VALUE,
                                               true)
          val lastModified = f.lastModified
          val updatedLastModified = 2000
          f.setLastModifiedTime(updatedLastModified)
          f.lastModified ==> updatedLastModified
          val cachedFile = dir.listEntries(f, Integer.MAX_VALUE, AllPass).get(0)
          cachedFile.value.lastModified ==> lastModified
        }
      }
      'newFields - withTempFileSync { f =>
        f.write("foo")
        val initialBytes = "foo".getBytes.toIndexedSeq
        val dir =
          FileTreeViews.cached[FileBytes](f.getParent,
                                          FileBytes(_: TypedPath),
                                          Integer.MAX_VALUE,
                                          true)
        def filter(bytes: Seq[Byte]): Filter[Entry[FileBytes]] =
          new Filter[Entry[FileBytes]] {
            override def accept(p: Entry[FileBytes]): Boolean = p.value.bytes == bytes
          }
        val cachedFile = dir.listEntries(f, Integer.MAX_VALUE, filter(initialBytes)).get(0)
        cachedFile.value.bytes ==> initialBytes
        f.write("bar")
        val newBytes = "bar".getBytes
        cachedFile.value.bytes ==> initialBytes
        f.getBytes ==> newBytes
        dir.update(TypedPaths.get(f, Entries.FILE))
        val newCachedFile = dir.listEntries(f, Integer.MAX_VALUE, filter(newBytes)).get(0)
        newCachedFile.value.bytes.toSeq ==> newBytes.toSeq
        dir.listEntries(f, Integer.MAX_VALUE, filter(initialBytes)).asScala.toSeq === Seq
          .empty[Path]
      }
    }
    'symlinks - {
      'indirect - {
        'remoteLink - withTempDirectory { dir =>
          withTempDirectorySync { otherDir =>
            val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
            val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
            val directory = newCachedView(dir, Integer.MAX_VALUE, followLinks = true)
            directory.ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                                 dirToOtherDirLink.resolve("dir"))
            otherDirToDirLink.delete()
            Files.createDirectory(otherDirToDirLink)
            val nestedFile = otherDirToDirLink.resolve("file").createFile()
            val file = dirToOtherDirLink.resolve("dir").resolve("file")
            directory.update(TypedPaths.get(dir, Entries.DIRECTORY))
            directory.ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                                 file.getParent,
                                                                 file)
          }
        }
        'localLink - withTempDirectory { dir =>
          withTempDirectorySync { otherDir =>
            val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
            val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
            val directory = newCachedView(dir, Integer.MAX_VALUE, followLinks = true)
            directory.ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                                 dirToOtherDirLink.resolve("dir"))
            dirToOtherDirLink.delete()
            Files.createDirectory(dirToOtherDirLink)
            val nestedFile = dirToOtherDirLink.resolve("file").createFile()
            directory.update(TypedPaths.get(dir, Entries.DIRECTORY))
            directory.ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink, nestedFile)
          }
        }
      }
      // This test is different from those above because it calls update with dirToOtherDirLink
      // rather than with dir
      'direct - withTempDirectory { dir =>
        withTempDirectorySync { otherDir =>
          val dirToOtherDirLink = Files.createSymbolicLink(dir.resolve("other"), otherDir)
          val otherDirToDirLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
          val directory = newCachedView(dir, Integer.MAX_VALUE, followLinks = true)
          directory.ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                               dirToOtherDirLink.resolve("dir"))
          dirToOtherDirLink.delete()
          Files.createDirectory(dirToOtherDirLink)
          val nestedFile = dirToOtherDirLink.resolve("file").createFile()
          directory.update(TypedPaths.get(dirToOtherDirLink, Entries.DIRECTORY))
          directory.ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink, nestedFile)
        }
      }
    }
  }

}
