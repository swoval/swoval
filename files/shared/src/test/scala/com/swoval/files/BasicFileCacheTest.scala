package com.swoval.files

import java.io.IOException
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, Path, Paths }

import FileTreeDataViews.Entry
import com.swoval.files.FileCacheTest.FileCacheOps
import com.swoval.files.test._
import com.swoval.functional.Filters.AllPass
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import TestHelpers._
import EntryOps._

trait BasicFileCacheTest extends TestSuite with FileCacheTest {
  val testsImpl: Tests = Tests {
    'directory - {
      'subdirectories - {
        'callback - withTempDirectory { dir =>
          val events = new ArrayBlockingQueue[Path](2)
          val eventSet = mutable.Set.empty[Path]
          usingAsync(simpleCache((cacheEntry: Entry[Path]) =>
            if (eventSet.add(cacheEntry.getTypedPath.getPath))
              events.add(cacheEntry.getTypedPath.getPath))) { c =>
            c.register(dir)
            withTempDirectory(dir) { subdir =>
              withTempFile(subdir) { f =>
                events.poll(DEFAULT_TIMEOUT)(e => assert(e == subdir || e == f)).flatMap { _ =>
                  events.poll(DEFAULT_TIMEOUT) { e =>
                    assert(e == subdir || e == f)
                    c.ls(dir).map(_.getTypedPath.getPath).toSet === Set(subdir, f)
                    ()
                  }
                }
              }
            }
          }
        }
      }
    }
    'files - {
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(simpleCache(_ => {})) { c =>
            c.reg(parent)
            c.ls(parent) === Seq(f)
          }
        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
                c.reg(dir)
                withTempFile(dir) { f =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Seq(f)
                  }
                }
              }
            }
            'directories - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
                c.reg(dir)
                withTempDirectory(dir) { subdir =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Seq(subdir)
                  }
                }
              }
            }
          }
        }
        'move - withTempDirectory { dir =>
          val latch = new CountDownLatch(2)
          val initial = Files.createTempFile(dir, "move", "")
          val moved = Paths.get(s"${initial.toString}.moved")
          val onChange = (_: Entry[Path]) => latch.countDown()
          val onUpdate = (_: Entry[Path], _: Entry[Path]) => {}
          val onError = (_: IOException) => {}
          val observer = getObserver(onChange, onUpdate, onChange, onError)
          usingAsync(FileCacheTest.getCached(false, identity, observer)) { c =>
            c.reg(dir, recursive = false)
            c.ls(dir, recursive = false) === Seq(initial)
            initial.renameTo(moved)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir, recursive = false) === Seq(moved)
            }
          }
        }

      }
    }
    'register - {
      'nonRecursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val latch = new CountDownLatch(1)
          usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
            c.reg(dir, recursive = false)
            withTempFile(subdir) { f =>
              assert(f.exists)
              subdir.setLastModifiedTime(3000)
              latch.waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(subdir)
              }
            }
          }
        }
      }
      'depth - {
        'initial - {
          withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((_: Entry[Path]) => {})) { c =>
                    c.register(dir, 1)
                    c.ls(dir) === Set(subdir, nestedSubdir)
                    c.register(dir, 2)
                    c.ls(dir) === Set(subdir, nestedSubdir, file)
                  }
                }
              }
            }
          }
        }
        'adjacent - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { nestedSubdir =>
              withTempFile(nestedSubdir) { file =>
                using(simpleCache((e: Entry[Path]) => {})) { c =>
                  c.register(dir, 0)
                  c.ls(dir) === Set(subdir)
                  c.register(subdir, Integer.MAX_VALUE)
                  c.ls(dir) === Set(subdir)
                  c.ls(subdir) === Set(nestedSubdir, file)
                }
              }
            }
          }
        }
        'overlap - {
          'infinite - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((e: Entry[Path]) => {})) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(subdir, Integer.MAX_VALUE)
                    c.ls(dir) === Set(subdir)
                    c.ls(subdir) === Set(nestedSubdir, file)
                    c.register(dir, Integer.MAX_VALUE)
                    c.ls(dir).sorted === Seq(subdir, nestedSubdir, file)
                  }
                }
              }
            }
          }
          'finite - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempDirectory(nestedSubdir) { nestedNestedSubdir =>
                  val file = nestedSubdir.resolve("file")
                  val latch = new CountDownLatch(1)
                  usingAsync(simpleCache((e: Entry[Path]) => {
                    if (e.getTypedPath.getPath == file) latch.countDown()
                  })) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(subdir, Integer.MAX_VALUE)
                    c.ls(dir) === Set(subdir)
                    c.ls(subdir) === Set(nestedSubdir, nestedNestedSubdir)
                    c.register(dir, 1)
                    c.ls(dir).sorted === Seq(subdir, nestedSubdir)
                    file.createFile()
                    latch.waitFor(DEFAULT_TIMEOUT) {
                      c.ls(subdir).sorted === Seq(nestedSubdir, nestedNestedSubdir, file)
                    }
                  }
                }
              }
            }
          }
        }
        'holes - {
          'limit - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((_: Entry[Path]) => {})) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(nestedSubdir, 0)
                    c.ls(dir) === Set(subdir)
                    c.ls(nestedSubdir) === Set(file)
                    val deep = Files.createDirectory(nestedSubdir.resolve("deep"))
                    val deepFile = Files.createFile(deep.resolve("file"))
                    c.register(nestedSubdir, 1)
                    c.ls(nestedSubdir) === Set(file, deep, deepFile)
                  }
                }
              }
            }
          }
          'unlimited - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((_: Entry[Path]) => {})) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(nestedSubdir, Integer.MAX_VALUE)
                    c.ls(dir) === Set(subdir)
                    c.ls(nestedSubdir) === Set(file)
                  }
                }
              }
            }
          }
        }
      }
      'recursive - {
        'initially - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[Path]) => {})) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'added - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[Path]) => {})) { c =>
                c.reg(dir, recursive = false)
                c.ls(dir) === Set(subdir)
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'removed - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[Path]) => {})) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
                c.register(dir, false)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
      }
    }
    'cache - {
      'update - withTempFile { file =>
        val latch = new CountDownLatch(1)
        usingAsync(
          FileCacheTest.getCached[LastModified](
            false,
            LastModified(_: TypedPath),
            new FileTreeDataViews.CacheObserver[LastModified] {
              override def onCreate(newEntry: Entry[LastModified]): Unit = {}

              override def onDelete(oldEntry: Entry[LastModified]): Unit = {}

              override def onUpdate(oldEntry: Entry[LastModified],
                                    newEntry: Entry[LastModified]): Unit =
                if (oldEntry.value.lastModified != newEntry.value.lastModified)
                  latch.countDown()

              override def onError(iOException: IOException): Unit = {}
            }
          )) { c =>
          c.reg(file.getParent, recursive = false)
          val cachedFile: Entry[LastModified] =
            c.ls(file.getParent, recursive = false) match {
              case Seq(f) if f.getTypedPath.getPath == file => f
              case p                                        => throw new IllegalStateException(p.toString)
            }
          val lastModified = cachedFile.value.lastModified
          lastModified ==> file.lastModified
          val updatedLastModified = 3000
          file.setLastModifiedTime(updatedLastModified)
          latch.waitFor(DEFAULT_TIMEOUT) {
            val newCachedFile: Entry[LastModified] =
              c.ls(file.getParent, recursive = false) match {
                case Seq(f) if f.getTypedPath.getPath == file => f
                case p                                        => throw new IllegalStateException(p.toString)
              }
            cachedFile.value.lastModified ==> lastModified
            newCachedFile.value.lastModified ==> updatedLastModified
          }
        }
      }
    }
    'registeredFiles - {
      'exists - withTempFile { file =>
        val latch = new CountDownLatch(1)
        usingAsync(simpleCache((e: Entry[Path]) => {
          if (e.getTypedPath.getPath.lastModified == 3000) latch.countDown()
        })) { c =>
          c.reg(file)
          file.setLastModifiedTime(3000)
          latch.waitFor(DEFAULT_TIMEOUT) {
            file.lastModified ==> 3000
          }
        }
      }
      'absent - withTempDirectory { dir =>
        val file = dir.resolve("file")
        val latch = new CountDownLatch(1)
        usingAsync(simpleCache((e: Entry[Path]) => {
          if (e.getTypedPath.getPath == file) latch.countDown()
        })) { c =>
          c.reg(file)
          file.createFile()
          latch.waitFor(DEFAULT_TIMEOUT) {
            c.ls(file) === Seq(file)
          }
        }
      }
      'replaced - withTempFile { file =>
        val dir = file.getParent
        val deletionLatch = new CountDownLatch(1)
        val newFileLatch = new CountDownLatch(1)
        val newFile = dir.resolve("new-file")
        val observer = new FileTreeDataViews.CacheObserver[Path] {
          override def onCreate(newEntry: Entry[Path]): Unit = {
            if (newEntry.getTypedPath.getPath == newFile) newFileLatch.countDown()
          }

          override def onDelete(oldEntry: Entry[Path]): Unit =
            if (oldEntry.getTypedPath.getPath == dir) deletionLatch.countDown()

          override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {}

          override def onError(exception: IOException): Unit = {}
        }
        usingAsync(FileCacheTest.getCached(false, identity, observer)) { c =>
          c.reg(dir)
          c.ls(dir) === Seq(file)
          dir.deleteRecursive()
          deletionLatch
            .waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir) === Seq.empty[Path]
              Files.createDirectories(dir)
              newFile.createFile()
            }
            .flatMap { _ =>
              newFileLatch.waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(newFile)
              }
            }
        }
      }
    }
    'list - {
      'entries - {
        'file - withTempFile { file =>
          using(simpleCache((_: Entry[Path]) => {})) { c =>
            c.reg(file)
            c.ls(file) === Seq(file)
            c.list(file, 0, AllPass).asScala.toSeq === Seq(TypedPaths.get(file))
          }
        }
        'directory - {
          'empty - withTempDirectory { dir =>
            using(simpleCache((_: Entry[Path]) => {})) { c =>
              c.reg(dir)
              c.ls(dir) === Seq.empty[Path]
              c.list(dir, 0, AllPass).asScala.toSeq === Seq.empty[TypedPath]
            }
          }
          'nonEmpty - withTempFile { file =>
            val dir = file.getParent
            using(simpleCache((_: Entry[Path]) => {})) { c =>
              c.reg(dir)
              c.ls(dir) === Seq(file)
              c.list(dir, 0, AllPass).asScala.toSeq === Seq(TypedPaths.get(file))
            }
          }
          'limit - withTempFile { file =>
            val dir = file.getParent
            using(simpleCache((_: Entry[Path]) => {})) { c =>
              c.register(dir, -1)
              c.ls(dir) === Seq(dir)
              c.list(dir, 0, AllPass).asScala.toSeq.map(_.getPath).headOption ==> Some(dir)
            }
          }
          'nonExistent - using(simpleCache((_: Entry[Path]) => {})) { c =>
            val dir = Paths.get("/foo")
            c.ls(dir) === Seq.empty[Path]
            c.list(dir, 0, AllPass).asScala.toSeq === Seq.empty[TypedPath]
          }
        }
      }
    }
    'callbacks - {
      'add - withTempDirectory { dir =>
        val file = dir.resolve("file")
        val creationLatch = new CountDownLatch(1)
        val updateLatch = new CountDownLatch(1)
        val deletionLatch = new CountDownLatch(1)
        usingAsync(
          lastModifiedCache(false)(
            (_: Entry[LastModified]) => creationLatch.countDown(),
            (_: Entry[LastModified], newEntry: Entry[LastModified]) =>
              if (newEntry.getValue.isRight && newEntry.getValue.get.lastModified == 3000)
                updateLatch.countDown(),
            (oldEntry: Entry[LastModified]) =>
              if (oldEntry.getTypedPath.getPath == file) deletionLatch.countDown()
          )) { c =>
          c.reg(dir)
          file.createFile()
          creationLatch
            .waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir) === Seq(file)
              Files.setLastModifiedTime(file, FileTime.fromMillis(3000))
            }
            .flatMap { _ =>
              updateLatch
                .waitFor(DEFAULT_TIMEOUT) {
                  Files.delete(file)
                }
                .flatMap { _ =>
                  deletionLatch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Seq.empty[Path]
                  }
                }
            }
        }
      }
      'remove - withTempDirectory { dir =>
        val latch = new CountDownLatch(1)
        var secondObserverFired = false
        usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
          val handle = c.addObserver(new FileTreeViews.Observer[Entry[Path]] {
            override def onNext(entry: Entry[Path]): Unit = secondObserverFired = true

            override def onError(t: Throwable): Unit = {}
          })
          c.reg(dir)
          c.removeObserver(handle)
          val file = Files.createFile(dir.resolve("file"))
          latch.waitFor(DEFAULT_TIMEOUT) {
            assert(!secondObserverFired)
            c.ls(dir) === Seq(file)
          }
        }
      }
    }
    'unregister - {
      'noop - withTempDirectory { dir =>
        using(simpleCache((_: Entry[Path]) => {})) { c =>
          c.unregister(dir)
          c.ls(dir) === Seq.empty[Path]
        }
      }
      'simple - withTempDirectory { root =>
        val dir = Files.createDirectories(root.resolve("unregister"))
        val file = dir.resolve("simple").createFile()
        val latch = new CountDownLatch(1)
        usingAsync(
          lastModifiedCache(true)(
            (_: Entry[LastModified]) => {},
            (_: Entry[LastModified], newEntry: Entry[LastModified]) =>
              if (newEntry.value.lastModified == 3000) latch.countDown(),
            (_: Entry[LastModified]) => {}
          )) { c =>
          c.register(file)
          c.ls(file) === Seq(file)
          c.unregister(file)
          c.ls(file) === Seq.empty[Path]
          file.setLastModifiedTime(3000)
          latch
            .waitFor(100.millis) {
              throw new IllegalStateException(
                s"Cache triggered for file that shouldn't be monitored: $file")
            }
            .recover {
              case _: TimeoutException => file.lastModified ==> 3000
            }
        }
      }
      'covered - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFile(subdir) { file =>
            val latch = new CountDownLatch(1)
            val secondLatch = new CountDownLatch(1)
            usingAsync(lastModifiedCache(true)(
              (_: Entry[LastModified]) => {},
              (_: Entry[LastModified], newEntry: Entry[LastModified]) =>
                newEntry.value.lastModified match {
                  case 3000 => latch.countDown()
                  case 4000 => secondLatch.countDown()
                  case _    =>
              },
              (_: Entry[LastModified]) => {}
            )) { c =>
              c.register(dir)
              c.register(file)
              c.ls(dir) === Set(subdir, file)
              // This should have no visible impact to the cache since file's parent is recursively
              // registered.
              c.unregister(file)
              c.ls(file) === Seq(file)
              c.ls(subdir) === Seq(file)
              file.setLastModifiedTime(3000)
              latch
                .waitFor(DEFAULT_TIMEOUT) {
                  file.lastModified ==> 3000
                  c.unregister(dir)
                  c.ls(dir) === Seq.empty[Path]
                  file.setLastModifiedTime(4000)
                }
                .flatMap { _ =>
                  // This will actually flush the entries from the cache.
                  secondLatch
                    .waitFor(100.millis) {
                      throw new IllegalStateException(
                        s"Cache triggered for file that shouldn't be monitored: $file")
                    }
                    .recover {
                      case _: TimeoutException => file.lastModified ==> 4000
                    }
                }
            }
          }
        }
      }
    }
  }
}

object BasicFileCacheTest extends BasicFileCacheTest with DefaultFileCacheTest {
  val tests = testsImpl
}

object NioBasicFileCacheTest extends BasicFileCacheTest with NioFileCacheTest {
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests('ignore - {
        println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
