package com.swoval.files

import java.nio.file.{ Files, Path => JPath }

import com.swoval.files.Directory._
import com.swoval.files.test._
import com.swoval.test._
import utest._
import utest.framework.ExecutionContext.RunNow

import scala.collection.JavaConverters._
import FileCacheTest.FileCacheOps

import scala.util.Failure

trait FileCacheTest extends TestSuite {
  def factory: DirectoryWatcher.Factory
  def identity: Converter[JPath] = (p: JPath) => p
  def simpleCache(f: Entry[JPath] => Unit): FileCache[JPath] =
    FileCache.apply(((p: JPath) => p): Converter[JPath],
                    Observers.apply(f: OnChange[JPath]),
                    factory)

  val testsImpl: Tests = Tests {
    'directory - {
      'subdirectories - {
        'callback - withTempDirectory { dir =>
          val events = new ArrayBlockingQueue[JPath](2)
          usingAsync(simpleCache((cacheEntry: Entry[JPath]) => events.add(cacheEntry.path))) { c =>
            c.register(dir)
            withTempDirectory(dir) { subdir =>
              withTempFile(subdir) { f =>
                events.poll(DEFAULT_TIMEOUT)(_ ==> subdir).flatMap { _ =>
                  events.poll(DEFAULT_TIMEOUT) { e =>
                    e ==> f
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
              usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
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
              usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
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
          val moved = Path(s"${initial.toString}.moved")
          val onChange: OnChange[JPath] = (_: Entry[JPath]) => latch.countDown()
          val onUpdate: OnUpdate[JPath] = (_: Entry[JPath], _: Entry[JPath]) => {}
          val observer = Observers.apply(onChange, onUpdate, onChange)
          usingAsync(FileCache.apply(identity, observer, factory)) { c =>
            c.reg(dir, recursive = false)
            c.ls(dir, recursive = false) === Seq(initial)
            initial.renameTo(moved)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir, recursive = false) === Seq(moved)
            }
          }
        }
        'addmany - withTempDirectory { dir =>
          // Windows is slow (at least on my vm)
          val subdirsToAdd = if (System.getProperty("java.vm.name", "") == "Scala.js") {
            if (Platform.isWin) 5 else 50
          } else 2000
          val timeout = if (Platform.isWin) DEFAULT_TIMEOUT * 60 else DEFAULT_TIMEOUT * 10
          val filesPerSubdir = 4
          val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
          val creationLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
          val deletionLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
          val subdirs = (1 to subdirsToAdd).map { i => dir.resolve(s"subdir-$i") }
          val files = subdirs.flatMap { subdir =>
            (1 to filesPerSubdir).map { j =>
              subdir.resolve(s"file-$j")
            }
          }
          var allFiles = (subdirs ++ files).toSet
          val observer = Observers.apply[JPath]((_: Entry[JPath]) => creationLatch.countDown(),
                                                (_: Entry[JPath], _: Entry[JPath]) => {},
                                                (e: Entry[JPath]) => deletionLatch.countDown())
          usingAsync(FileCache.apply[JPath](identity, observer, factory)) { c =>
            c.reg(dir)
            executor.run(new Runnable {
              override def run(): Unit = {
                subdirs.foreach(Files.createDirectories(_))
                files.foreach(Files.createFile(_))
              }
            })
            creationLatch
              .waitFor(timeout) {
                val found = c.ls(dir).map(_.path).toSet
                // Need to synchronize since files is first set on a different thread
                allFiles.synchronized { found === allFiles }
              }
              .flatMap { _ =>
                executor.run(new Runnable {
                  override def run(): Unit = {
                    files.foreach(Files.deleteIfExists(_))
                    subdirs.foreach(Files.deleteIfExists(_))
                  }
                })
                deletionLatch.waitFor(timeout) {
                  c.ls(dir) === Seq.empty
                }
              }
          }.andThen {
            case Failure(e) =>
              println(s"Task failed $e")
              executor.close()
              if (creationLatch.getCount > 0)
                println(s"$this Creation latch not triggered (${creationLatch.getCount})")
              if (deletionLatch.getCount > 0)
                println(s"$this Deletion latch not triggered (${deletionLatch.getCount})")
            case _ =>
              executor.close()
          }
        }
      }
    }
    'register - {
      'nonRecursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val latch = new CountDownLatch(1)
          usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
            c.reg(dir, recursive = false)
            withTempFile(subdir) { f =>
              assert(f.exists)
              subdir.setLastModifiedTime(2000)
              latch.waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(subdir)
              }
            }
          }
        }
      }
      'recursive - {
        'initially - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'added - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { f =>
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
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
            withTempFileSync(subdir) { f =>
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
                c.reg(dir, recursive = false)
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
          FileCache.apply[LastModified](
            LastModified(_),
            new Observer[LastModified] {
              override def onCreate(newEntry: Entry[LastModified]): Unit = {}
              override def onDelete(oldEntry: Entry[LastModified]): Unit = {}
              override def onUpdate(oldEntry: Entry[LastModified],
                                    newEntry: Entry[LastModified]): Unit =
                if (oldEntry.value.lastModified != newEntry.value.lastModified) latch.countDown()
            },
            factory
          )) { c =>
          c.reg(file.getParent, recursive = false)
          val cachedFile: Entry[LastModified] =
            c.ls(file.getParent, recursive = false) match {
              case Seq(f) if f.path == file => f
              case p                        => throw new IllegalStateException(p.toString)
            }
          val lastModified = cachedFile.value.lastModified
          lastModified ==> file.lastModified
          val updatedLastModified = 2000
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
  }
}

object FileCacheTest {
  implicit class FileCacheOps[T <: AnyRef](val fileCache: FileCache[T]) extends AnyVal {
    def ls(dir: JPath,
           recursive: Boolean = true,
           filter: EntryFilter[_ >: T] = EntryFilters.AllPass): Seq[Entry[T]] =
      fileCache.list(dir, recursive, filter).asScala
    def reg(dir: JPath, recursive: Boolean = true): Directory[T] =
      fileCache.register(dir, recursive)
  }
}

object DefaultFileCacheTest extends FileCacheTest {
  val factory = DirectoryWatcher.DEFAULT_FACTORY
  val tests = testsImpl
}

object NioFileCacheTest extends FileCacheTest {
  val factory = new DirectoryWatcher.Factory {
    override def create(callback: DirectoryWatcher.Callback): DirectoryWatcher =
      new NioDirectoryWatcher(callback)
  }
  val tests =
    if (Platform.isMac && System.getProperty("java.vm.name") != "Scala.js") testsImpl
    else
      Tests('ignore - {
        println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
