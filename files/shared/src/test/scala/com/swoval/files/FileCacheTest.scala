package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import com.swoval.files.PathFilter.AllPass
import com.swoval.files.apple.Flags
import com.swoval.files.test._
import com.swoval.test._
import utest._
import utest.framework.ExecutionContext.RunNow

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Properties

object FileCacheTest extends TestSuite {
  val tests: Tests = Tests {
    val options = Options.default
    'files - {
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(FileCache(options)(FileWatchEvent.Ignore)) { c =>
            c.register(parent)
            c.list(parent, recursive = true, AllPass) === Seq(f)
          }
        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(FileCache(options)((_: FileWatchEvent[Path]) => latch.countDown())) { c =>
                c.register(dir)
                withTempFile(dir) { f =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.list(dir, recursive = true, AllPass) === Seq(f)
                  }
                }
              }
            }
            'directories - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(FileCache(options)((_: FileWatchEvent[Path]) => latch.countDown())) { c =>
                c.register(dir)
                withTempDirectory(dir) { subdir =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.list(dir, recursive = true, AllPass) === Seq(subdir)
                  }
                }
              }
            }
          }
        }
        'move - withTempDirectory { dir =>
          val latch = new CountDownLatch(2)
          val initial = Path.createTempFile(dir, "move")
          val moved = Path(s"${initial.name}.moved")
          usingAsync(FileCache(options)((_: FileWatchEvent[Path]) => latch.countDown())) { c =>
            c.list(dir, recursive = false, AllPass) === Seq(initial)
            initial.renameTo(moved)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.list(dir, recursive = false, AllPass) === Seq(moved)
            }
          }
        }
        "anti-entropy" - withTempFile { f =>
          val parent = f.getParent
          val events = new ArrayBlockingQueue[FileWatchEvent[Path]](2)
          val executor: ScheduledExecutor = platform.makeScheduledExecutor("FileCacheTest")
          val flags = new Flags.Create().setFileEvents.setNoDefer
          val latency = 40.milliseconds
          val options = Options(latency, flags)
          usingAsync(FileCache(options)(events.add)) { c =>
            c.register(parent)
            c.list(parent, recursive = true, AllPass) === Seq(f)
            val callbacks = Callbacks[Path]()
            val lastModified = 1000
            var handle = -1
            usingAsync(DirectoryWatcher.default(1.millis)(callbacks)) { w =>
              handle = callbacks.addCallback { _ =>
                executor.schedule(5.milliseconds) {
                  callbacks.removeCallback(handle)
                  f.setLastModifiedTime(lastModified)
                }
                w.close()
              }
              w.register(parent)
              f.setLastModifiedTime(0)
              events.poll(DEFAULT_TIMEOUT)(_.kind ==> Modify).flatMap { _ =>
                f.lastModified ==> lastModified
                executor.schedule(latency / 2)(f.delete())
                events.poll(DEFAULT_TIMEOUT)(_.kind ==> Delete)
              }
            }
          }
        }
        'addmany - withTempDirectory { dir =>
          val filesToAdd = 100
          val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
          val latch = new CountDownLatch(filesToAdd * 2)
          val added = mutable.Set.empty[Path]
          val callback: Callback = e =>
            added.synchronized {
              if (e.kind == Create && added.add(e.path)) {
                latch.countDown()
              }
          }
          usingAsync(FileCache(options)(callback)) { c =>
            c.register(dir)
            val files = mutable.Set.empty[Path]
            executor.run {
              (0 until filesToAdd) foreach { i =>
                val subdir = Path.createTempDirectory(dir, s"subdir-$i-")
                val file = Path.createTempFile(subdir, s"file-$i-")
                files ++= Seq(subdir, file)
              }
            }
            latch.waitFor(DEFAULT_TIMEOUT * 10) {
              added.clear()
              val found = c.list(dir, recursive = true, PathFilter.AllPass).toSet
              found === files.toSet
            }
          }.andThen { case _ => executor.close() }
        }
      }
    }
    'cache - {
      'update - withTempFile { file =>
        val latch = new CountDownLatch(1)
        usingAsync(FileCache[CachedLastModified](options)((_: FileWatchEvent[CachedLastModified]) =>
          latch.countDown())) { c =>
          val cachedFile: Path = c.list(file.getParent, recursive = false, AllPass) match {
            case Seq(f) if f == file => f
            case p                   => throw new IllegalStateException(p.toString)
          }
          val lastModified = cachedFile.lastModified
          lastModified ==> file.lastModified
          val updatedLastModified = 2000
          file.setLastModifiedTime(updatedLastModified)
          latch.waitFor(DEFAULT_TIMEOUT) {
            val newCachedFile: Path = c.list(file.getParent, recursive = false, AllPass) match {
              case Seq(f) if f == file => f
              case p                   => throw new IllegalStateException(p.toString)
            }
            assert(cachedFile.lastModified == lastModified)
            newCachedFile.lastModified ==> updatedLastModified
          }
        }
      }
    }
  }
}
