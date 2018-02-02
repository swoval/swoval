package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.Create
import com.swoval.files.test._
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.util.Properties

object FileCacheTest extends TestSuite {
  val tests: Tests = Tests {
    'files - {
      val options = Options.default
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(FileCache(options)(_ => {})) { c =>
            c.register(parent)
            c.list(parent, recursive = true, _ => true) === Seq(f)
          }
        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(FileCache(options)(_ => latch.countDown())) { c =>
                c.register(dir)
                withTempFile(dir) { f =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.list(dir, recursive = true, _ => true) === Seq(f)
                  }
                }
              }
            }
            'directories - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(FileCache(options)(_ => latch.countDown())) { c =>
                c.register(dir)
                withTempDirectory(dir) { subdir =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.list(dir, recursive = true, _ => true) === Seq(subdir)
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
          usingAsync(FileCache(options)(_ => latch.countDown())) { c =>
            c.list(dir, recursive = false, _ => true) === Seq(initial)
            initial.renameTo(moved)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.list(dir, recursive = false, _ => true) === Seq(moved)
            }
          }
        }
        'addmany - withTempDirectory { dir =>
          val filesToAdd = if (Properties.isMac) 100 else 5
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
            val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
            val files = mutable.Set.empty[Path]
            executor.run {
              (0 until filesToAdd) foreach { i =>
                val subdir = Path.createTempDirectory(dir, s"subdir-$i-")
                val file = Path.createTempFile(subdir, s"file-$i-")
                files ++= Seq(subdir, file)
              }
            }
            try {
              latch.waitFor(DEFAULT_TIMEOUT * 10) {
                added.clear()
                val found = c.list(dir, recursive = true, _ => true).toSet
                found === files.toSet
              }
            } finally {
              executor.close()
            }
          }
        }
      }
      'reuseDirectories - withTempDirectory { dir =>
        val directory = Directory(dir)
        val directories = mutable.Set(directory)
        val latch = new CountDownLatch(1)
        usingAsync(new FileCacheImpl(options, directories) {
          override def close() = closeImpl(clearDirectoriesOnClose = false)
        }) { c =>
          c.addCallback(_ => latch.countDown())
          withTempFile(dir) { f =>
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.list(dir, recursive = true, _ => true) === Seq(f)
              c.close()
              directories.toSet === Set(directory)
            }
          }
        }
      }
    }
  }
}
