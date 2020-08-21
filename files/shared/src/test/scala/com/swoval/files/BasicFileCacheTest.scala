package com
package swoval
package files

import java.io.IOException
import java.nio.file.{ Files, Path, Paths }

import com.swoval.files.FileCacheTest.FileCacheOps
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.TestHelpers.EntryOps._
import com.swoval.files.TestHelpers._
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

trait BasicFileCacheTest extends TestSuite with FileCacheTest {
  def ignore[T]: Entry[T] => Unit = (_: Entry[T]) => ()
  def ignoreOld[T](f: Entry[T] => Unit): (Entry[T], Entry[T]) => Unit = (_, e) => f(e)
  val testsImpl: Tests = Tests {
    'directory - {
      'subdirectories - {
        'callback - withTempDirectory { dir =>
          implicit val logger: TestLogger = new CachingLogger
          val events = new ArrayBlockingQueue[Path](2)
          val eventSet = mutable.Set.empty[Path]
          usingAsync(
            simpleCache((cacheEntry: Entry[Path]) =>
              if (cacheEntry.path != dir && eventSet.add(cacheEntry.path))
                events.add(cacheEntry.path)
            )
          ) { c =>
            c.register(dir)
            withTempDirectory(dir) { subdir =>
              withTempFile(subdir) { f =>
                events.poll(DEFAULT_TIMEOUT)(e => assert(e == subdir || e == f)).flatMap { _ =>
                  events.poll(DEFAULT_TIMEOUT) { e =>
                    assert(e == subdir || e == f)
                    c.ls(dir).map(_.path).toSet === Set(subdir, f)
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
          implicit val logger: TestLogger = new CachingLogger
          val parent = f.getParent
          using(simpleCache(ignore)) { c =>
            c.reg(parent)
            c.ls(parent) === Seq(f)
          }
        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              implicit val logger: TestLogger = new CachingLogger
              val latch = new CountDownLatch(1)
              val file = dir.resolve("file")
              usingAsync(simpleCache((e: Entry[Path]) => {
                if (e.path == file) latch.countDown()
              })) { c =>
                c.reg(dir)
                file.createFile()
                latch.waitFor(DEFAULT_TIMEOUT) {
                  c.ls(dir) === Seq(file)
                }
              }
            }
            'directories - withTempDirectory { dir =>
              implicit val logger: TestLogger = new CachingLogger
              val latch = new CountDownLatch(1)
              usingAsync(
                simpleCache((e: Entry[Path]) => if (e.path.getParent == dir) latch.countDown())
              ) { c =>
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
          implicit val logger: TestLogger = new CachingLogger
          val latch = new CountDownLatch(1)
          val initial = dir createTempFile "move"
          val moved = Paths.get(s"${initial.toString}.moved")
          val onChange = (e: Entry[Path]) => if (e.path == moved) latch.countDown()
          val onUpdate = ignoreOld[Path](ignore)
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
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory(dir) { subdir =>
          val latch = new CountDownLatch(1)
          val file = subdir.resolve("file")
          usingAsync(simpleCache((e: Entry[Path]) => if (e.path == subdir) latch.countDown())) {
            c =>
              c.reg(dir, recursive = false)
              file.createFile()
              assert(file.exists)
              subdir.setLastModifiedTime(3000)
              latch.waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(subdir)
              }
          }
        }
      }
      'depth - {
        'initial - {
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache(ignore)) { c =>
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
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { nestedSubdir =>
              withTempFile(nestedSubdir) { file =>
                using(simpleCache(ignore)) { c =>
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
            implicit val logger: TestLogger = new CachingLogger
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache(ignore)) { c =>
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
            implicit val logger: TestLogger = new CachingLogger
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempDirectory(nestedSubdir) { nestedNestedSubdir =>
                  val file = nestedSubdir.resolve("file")
                  val latch = new CountDownLatch(1)
                  usingAsync(simpleCache((e: Entry[Path]) => {
                    if (e.path == file) latch.countDown()
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
          'deeplyNested - withTempDirectory { dir =>
            implicit val logger: TestLogger = new CachingLogger
            withTempDirectory(dir) { subdir =>
              val nestedSubdir = subdir.resolve("nested").createDirectories()
              val deeplyNestedSubdir =
                subdir.resolve("very").resolve("deeply").resolve("nested").createDirectories()
              val file = deeplyNestedSubdir.resolve("file").createFile()
              using(simpleCache(ignore)) { c =>
                c.register(dir, 0)
                c.register(nestedSubdir, Integer.MAX_VALUE)
                c.ls(deeplyNestedSubdir) ==> Nil
                c.register(deeplyNestedSubdir, Integer.MAX_VALUE)
                c.ls(deeplyNestedSubdir).sorted === Seq(file)
              }
            }
          }
        }
        'holes - {
          'limit - withTempDirectory { dir =>
            implicit val logger: TestLogger = new CachingLogger
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache(ignore)) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(nestedSubdir, 0)
                    c.ls(dir) === Set(subdir)
                    c.ls(nestedSubdir) === Set(file)
                    val deep = nestedSubdir.resolve("deep").createDirectory()
                    val deepFile = deep.resolve("file").createFile()
                    c.register(nestedSubdir, 1)
                    c.ls(nestedSubdir) === Set(file, deep, deepFile)
                  }
                }
              }
            }
          }
          'unlimited - withTempDirectory { dir =>
            implicit val logger: TestLogger = new CachingLogger
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache(ignore)) { c =>
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
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache(ignore)) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'added - withTempDirectory { dir =>
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache(ignore)) { c =>
                c.reg(dir, recursive = false)
                c.ls(dir) === Set(subdir)
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'removed - withTempDirectory { dir =>
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache(ignore)) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
                c.register(dir, false)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
      }
      'relative - withTempDirectory(targetDir) { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val latch = new CountDownLatch(1)
        val file = dir.resolve("file")
        val callback =
          (e: Entry[Path]) => if (e.path.getFileName.toString == "file") latch.countDown()
        usingAsync(simpleCache(callback)) { w =>
          w.register(baseDir.relativize(dir))
          file.createFile()
          latch.waitFor(DEFAULT_TIMEOUT) {
            assert(file.exists)
          }
        }
      }
      'order - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory(dir) { subdir =>
          val latch = new CountDownLatch(1)
          val file = subdir.resolve("file")
          usingAsync(simpleCache((e: Entry[Path]) => if (e.path == file) latch.countDown())) { c =>
            c.reg(subdir, false)
            c.ls(subdir) === Set.empty[Path]
            c.register(dir, false)
            c.ls(dir) === Set(subdir)
            file.createFile()
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir) === Set(subdir)
              c.ls(subdir) === Set(file)
            }
          }
        }
      }
      'missing - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory(dir) { subdir =>
          val latch = new CountDownLatch(1)
          val subdir2 = subdir.resolve("sub")
          val subdir3 = subdir2.resolve("sub")
          val subdir4 = subdir3.resolve("sub")
          val file = subdir4.resolve("file")
          usingAsync(simpleCache((e: Entry[Path]) => if (e.path == file) latch.countDown())) { c =>
            c.reg(subdir4, true)
            c.ls(subdir4) === Set.empty[Path]
            Files.createDirectories(subdir4)
            file.createFile()
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(subdir3) === Set.empty[Path]
              subdir3.toFile.listFiles.toSet === Set(subdir4.toFile)
              c.ls(subdir4) === Set(file)
            }
          }
        }
      }
    }
    'cache - {
      'update - withTempFile { file =>
        implicit val logger: TestLogger = new CachingLogger
        val latch = new CountDownLatch(1)
        usingAsync(
          FileCacheTest.getCached[LastModified](
            false,
            LastModified(_: TypedPath),
            new FileTreeDataViews.CacheObserver[LastModified] {
              override def onCreate(newEntry: Entry[LastModified]): Unit = {}

              override def onDelete(oldEntry: Entry[LastModified]): Unit = {}

              override def onUpdate(oldEntry: Entry[LastModified], newEntry: Entry[LastModified])
                  : Unit =
                if (oldEntry.value.lastModified != newEntry.value.lastModified)
                  latch.countDown()

              override def onError(iOException: IOException): Unit = {}
            }
          )
        ) { c =>
          c.reg(file.getParent, recursive = false)
          val cachedFile: Entry[LastModified] =
            c.ls(file.getParent, recursive = false) match {
              case s if s.size == 1 && s.head.path == file => s.head
              case p                                       => throw new IllegalStateException(p.toString)
            }
          val lastModified = cachedFile.value.lastModified
          lastModified ==> file.lastModified
          val updatedLastModified = 3000
          file.setLastModifiedTime(updatedLastModified)
          latch.waitFor(DEFAULT_TIMEOUT) {

            val newCachedFile: Entry[LastModified] =
              c.ls(file.getParent, recursive = false) match {
                case Seq(f) if f.path == file => f
                case p                        => throw new IllegalStateException(p.toString)
              }
            cachedFile.value.lastModified ==> lastModified
            newCachedFile.value.lastModified ==> updatedLastModified
          }
        }
      }
    }
    'registeredFiles - {
      'exists - withTempFile { file =>
        implicit val logger: TestLogger = new CachingLogger
        val latch = new CountDownLatch(1)
        usingAsync(simpleCache((e: Entry[Path]) => {
          if (e.path.lastModified == 3000) latch.countDown()
        })) { c =>
          c.reg(file)
          file.setLastModifiedTime(3000)
          latch.waitFor(DEFAULT_TIMEOUT) {
            file.lastModified ==> 3000
          }
        }
      }
      'absent - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val _ = "DEBUG PLEASE"
        val file = dir.resolve("file")
        val latch = new CountDownLatch(1)
        usingAsync(simpleCache((e: Entry[Path]) => {
          if (e.path == file) latch.countDown()
        })) { c =>
          c.reg(file)
          file.createFile()
          latch.waitFor(DEFAULT_TIMEOUT) {
            c.ls(file) === Seq(file)
          }
        }
      }
      'replaced - withTempDirectory { root =>
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("register-replace").createDirectories()
        withTempFile(dir) { file =>
          val deletionLatch = new CountDownLatch(2)
          val newFileLatch = new CountDownLatch(1)
          val newFile = dir.resolve("new-file")
          val observer = new FileTreeDataViews.CacheObserver[Path] {
            override def onCreate(newEntry: Entry[Path]): Unit = {
              if (newEntry.path == newFile) newFileLatch.countDown()
            }

            override def onDelete(oldEntry: Entry[Path]): Unit =
              if (oldEntry.path == dir || oldEntry.path == file) deletionLatch.countDown()

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
                newFile.createFile(mkdirs = true)
              }
              .flatMap { _ =>
                newFileLatch.waitFor(DEFAULT_TIMEOUT) {
                  c.ls(dir) === Seq(newFile)
                }
              }
          }
        }
      }
    }
    'list - {
      'entries - {
        'file - withTempFile { file =>
          implicit val logger: TestLogger = new CachingLogger
          using(simpleCache(ignore)) { c =>
            c.reg(file)
            c.ls(file) === Seq(file)
            c.list(file, 0, AllPass).asScala.toSeq === Seq(TypedPaths.get(file))
          }
        }
        'directory - {
          'empty - withTempDirectory { dir =>
            implicit val logger: TestLogger = new CachingLogger
            using(simpleCache(ignore)) { c =>
              c.reg(dir)
              c.ls(dir) === Seq.empty[Path]
              c.list(dir, 0, AllPass).asScala.toSeq === Seq.empty[TypedPath]
            }
          }
          'nonEmpty - withTempFile { file =>
            implicit val logger: TestLogger = new CachingLogger
            val dir = file.getParent
            using(simpleCache(ignore)) { c =>
              c.reg(dir)
              c.ls(dir) === Seq(file)
              c.list(dir, 0, AllPass).asScala.toSeq === Seq(TypedPaths.get(file))
            }
          }
          'limit - withTempFile { file =>
            implicit val logger: TestLogger = new CachingLogger
            val dir = file.getParent
            using(simpleCache(ignore)) { c =>
              c.register(dir, -1)
              c.ls(dir) === Seq(dir)
              c.list(dir, 0, AllPass).asScala.toSeq.map(_.getPath).headOption ==> Some(dir)
            }
          }
          'nonExistent - {
            implicit val logger: TestLogger = new CachingLogger
            using(simpleCache(ignore)) { c =>
              val dir = Paths.get("/foo")
              c.ls(dir) === Seq.empty[Path]
              c.list(dir, 0, AllPass).asScala.toSeq === Seq.empty[TypedPath]
            }
          }
        }
      }
    }
    'callbacks - {
      'add - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val file = dir.resolve("file")
        val creationLatch = new CountDownLatch(1)
        val updateLatch = new CountDownLatch(1)
        val deletionLatch = new CountDownLatch(1)
        usingAsync(
          lastModifiedCache(false)(
            (e: Entry[LastModified]) => if (e.path == file) creationLatch.countDown(),
            (e: Entry[LastModified], newEntry: Entry[LastModified]) =>
              if (newEntry.getValue.isRight && newEntry.getValue.get.lastModified == 3000)
                updateLatch.countDown(),
            (oldEntry: Entry[LastModified]) => if (oldEntry.path == file) deletionLatch.countDown()
          )
        ) { c =>
          c.reg(dir)
          file.createFile()
          creationLatch
            .waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir) === Seq(file)
              file setLastModifiedTime 3000
            }
            .flatMap { _ =>
              updateLatch
                .waitFor(DEFAULT_TIMEOUT) {
                  file.delete()
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
        implicit val logger: TestLogger = new CachingLogger
        val latch = new CountDownLatch(1)
        var secondObserverFired = false
        val file = dir.resolve("file")
        usingAsync(simpleCache((e: Entry[Path]) => if (e.entry.path == file) latch.countDown())) {
          c =>
            val handle = c.addObserver(new FileTreeViews.Observer[Entry[Path]] {
              override def onNext(entry: Entry[Path]): Unit =
                if (entry.path == file) secondObserverFired = true
              override def onError(t: Throwable): Unit = {}
            })
            c.reg(dir)
            c.removeObserver(handle)
            file.createFile()
            latch.waitFor(DEFAULT_TIMEOUT) {
              assert(!secondObserverFired)
              c.ls(dir) === Seq(file)
            }
        }
      }
    }
    'unregister - {
      'noop - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        using(simpleCache(ignore)) { c =>
          c.unregister(dir)
          c.ls(dir) === Seq.empty[Path]
        }
      }
      'simple - withTempDirectory { root =>
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("unregister").createDirectories()
        val file = dir.resolve("simple").createFile()
        val latch = new CountDownLatch(1)
        usingAsync(
          lastModifiedCache(true)(
            ignore,
            ignoreOld { newEntry: Entry[LastModified] =>
              if (newEntry.value.lastModified == 3000) latch.countDown()
            },
            ignore
          )
        ) { c =>
          c.register(file)
          c.ls(file) === Seq(file)
          c.unregister(file)
          c.ls(file) === Seq.empty[Path]
          file.setLastModifiedTime(3000)
          latch
            .waitFor(100.millis) {
              throw new IllegalStateException(
                s"Cache triggered for file that shouldn't be monitored: $file"
              )
            }
            .recover {
              case _: TimeoutException => file.lastModified ==> 3000
            }
        }
      }
      'covered - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory(dir) { subdir =>
          withTempFile(subdir) { file =>
            val latch = new CountDownLatch(1)
            val secondLatch = new CountDownLatch(1)
            usingAsync(
              lastModifiedCache(true)(
                ignore,
                ignoreOld { newEntry: Entry[LastModified] =>
                  newEntry.value.lastModified match {
                    case 3000 => latch.countDown()
                    case 4000 => secondLatch.countDown()
                    case _    =>
                  }
                },
                ignore
              )
            ) { c =>
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
                        s"Cache triggered for file that shouldn't be monitored: $file"
                      )
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
        if (swoval.test.verbose)
          println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
