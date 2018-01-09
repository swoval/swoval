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
    testOn(MacOS) {
      "directories" - {
        val (fileOptions, dirOptions) = (NoMonitor, DirectoryOptions.default)
        'register - {
          'existing - withTempFile { f =>
            val parent = f.getParent
            using(FileCache(fileOptions, dirOptions)(_ => {})) { c =>
              c.register(parent)
              c.list(parent, recursive = true, _ => true) === Seq(f)
            }
          }
          'monitor - withTempDirectory { dir =>
            val latch = new CountDownLatch(1)
            usingAsync(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
              c.register(dir)
              withTempFile(dir) { f =>
                latch.waitFor(2 * DEFAULT_TIMEOUT) {
                  c.list(dir, recursive = true, _ => true) === Seq(f)
                }
              }
            }
          }
        }
      }
    }
    'files - {
      val (fileOptions, dirOptions) = (FileOptions.default, NoMonitor)
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(FileCache(fileOptions, dirOptions)(_ => {})) { c =>
            c.register(parent)
            c.list(parent, recursive = true, _ => true) === Seq(f)
          }
        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
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
              usingAsync(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
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
          usingAsync(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
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
          usingAsync(FileCache(fileOptions, dirOptions)(callback)) { c =>
            c.register(dir)
            val executor = Executor.make
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
    }
  }
}
object FileCacheDirectoryTest extends TestSuite {
  val tests: Tests = testOn(MacOS) {
    val (fileOptions, dirOptions) = (NoMonitor, DirectoryOptions.default)
    'register - {
      'existing - withTempFile { f =>
        val parent = f.getParent
        using(FileCache(fileOptions, dirOptions)(_ => {})) { c =>
          c.register(parent)
          c.list(parent, recursive = true, _ => true) === Seq(f)
        }
      }
      'monitor - withTempDirectory { dir =>
        val latch = new CountDownLatch(1)
        usingAsync(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
          c.register(dir)
          withTempFile(dir) { f =>
            latch.waitFor(2 * DEFAULT_TIMEOUT) {
              c.list(dir, recursive = true, _ => true) === Seq(f)
            }
          }
        }
      }
    }
  }
}
