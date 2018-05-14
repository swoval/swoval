package com.swoval.files

import java.nio.file.{ Files, Path => JPath }

import com.swoval.files.Directory.{ Converter, Entry }
import com.swoval.files.Directory.{ Observer, OnChange, OnUpdate }
import com.swoval.files.DirectoryWatcher.Event.{ Create, Modify }
import com.swoval.files.test._
import com.swoval.test._
import utest._
import utest.framework.ExecutionContext.RunNow

import scala.collection.JavaConverters._
import scala.collection.mutable

object FileCacheTest extends TestSuite {
  implicit class FileCacheOps[T <: AnyRef](val fileCache: FileCache[T]) extends AnyVal {
    def ls(dir: JPath,
           recursive: Boolean = true,
           filter: EntryFilter[_ >: T] = EntryFilters.AllPass): Seq[Entry[T]] =
      fileCache.list(dir, recursive, filter).asScala
    def reg(dir: JPath, recursive: Boolean = true) = fileCache.register(dir, recursive)
  }
  def identity: Converter[JPath] = (p: JPath) => p
  def simpleCache(f: Entry[JPath] => Unit): FileCache[JPath] =
    FileCache.apply(((p: JPath) => p): Converter[JPath], Observers.apply(f: OnChange[JPath]))

  val tests: Tests = Tests {
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
          usingAsync(FileCache.apply(identity, observer)) { c =>
            c.reg(dir, recursive = false)
            c.ls(dir, recursive = false) === Seq(initial)
            initial.renameTo(moved)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir, recursive = false) === Seq(moved)
            }
          }
        }
        'addmany - withTempDirectory { dir =>
          val filesToAdd = 100
          val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
          val latch = new CountDownLatch(filesToAdd * 2)
          val added = mutable.Set.empty[JPath]
          val onCreate: OnChange[JPath] = (cacheEntry: Entry[JPath]) => {
            added.synchronized {
              if (added.add(cacheEntry.path)) {
                latch.countDown()
              }
            }
          }
          val observer = Observers.apply(onCreate,
                                         (_: Entry[JPath], _: Entry[JPath]) => {},
                                         (_: Entry[JPath]) => {})
          usingAsync(FileCache.apply(identity, observer)) { c =>
            c.reg(dir)
            val files = mutable.Set.empty[JPath]
            executor.run(new Runnable {
              override def run(): Unit = {
                (0 until filesToAdd) foreach { i =>
                  val subdir = Files.createTempDirectory(dir, s"subdir-$i-")
                  val file = Files.createTempFile(subdir, s"file-$i-", "")
                  files ++= Seq(subdir, file)
                }
              }
            })
            latch.waitFor(DEFAULT_TIMEOUT) {
              added.clear()
              val found = c.ls(dir).toSet
              found.map(_.path) === files.toSet
            }
          }.andThen { case _ => executor.close() }
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
                if (oldEntry != newEntry) latch.countDown()
            }
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
