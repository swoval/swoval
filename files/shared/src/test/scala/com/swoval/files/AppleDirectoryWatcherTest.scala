package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.FileWatchEvent.{ Create, Delete, Modify }
import com.swoval.files.apple.Flags
import com.swoval.files.test.{ ArrayBlockingQueue, _ }
import com.swoval.test._
import utest._

import scala.concurrent.duration._

object AppleDirectoryWatcherTest extends TestSuite {
  val DEFAULT_LATENCY = 5.milliseconds
  val dirFlags = new Flags.Create().setNoDefer()
  val fileFlags = new Flags.Create().setNoDefer().setFileEvents()
  val tests = testOn(MacOS) {
    "directories" - {
      'onCreate - {
        withTempDirectory { dir =>
          assert(dir.exists)
          val events = new ArrayBlockingQueue[FileWatchEvent](10)
          val callback: Callback = e => events.add(e)

          usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, dirFlags)(callback)) { w =>
            w.register(dir)
            val f = dir.resolve(Path("foo")).createFile()
            events.poll(DEFAULT_TIMEOUT)(_.path === dir)
          }
        }
      }
      'onModify - withTempDirectory { dir =>
        val events = new ArrayBlockingQueue[FileWatchEvent](10)
        val callback: Callback = e => events.add(e)

        usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, dirFlags)(callback)) { w =>
          val f = dir.resolve(Path("foo")).createFile()
          w.register(dir)
          f.setLastModifiedTime(0L)
          f.delete()
          f.createFile()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'onDelete - withTempDirectory { dir =>
        val events = new ArrayBlockingQueue[FileWatchEvent](10)
        val callback: Callback = e => events.add(e)

        usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, dirFlags)(callback)) { w =>
          val f = dir.resolve(Path("foo")).createFile()
          w.register(dir)
          f.delete()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'subdirectories - {
        'onCreate - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val events = new ArrayBlockingQueue[FileWatchEvent](10)
            val callback: Callback = e => if (e.path != dir) events.add(e)

            usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, dirFlags)(callback)) { w =>
              w.register(dir)
              subdir.resolve(Path("foo")).createFile()
              events.poll(DEFAULT_TIMEOUT)(_.path === subdir)
            }
          }
        }
      }
    }
    'files - {
      "handle file creation events" - withTempDirectory { dir =>
        val events = new ArrayBlockingQueue[FileWatchEvent](10)
        val callback: Callback = e => { if (!e.path.isDirectory) events.add(e) }

        usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, fileFlags)(callback)) { w =>
          w.register(dir)
          dir.resolve(Path("foo")).createFile()
          events.poll(DEFAULT_TIMEOUT)(_.kind ==> Create)
        }
      }
      "handle file touch events" - withTempFile { f =>
        val events = new ArrayBlockingQueue[FileWatchEvent](10)
        val callback: Callback = e => if (!e.path.isDirectory && e.kind != Create) events.add(e)
        usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, fileFlags)(callback)) { w =>
          w.register(f.getParent)
          f.setLastModifiedTime(0L)
          events.poll(DEFAULT_TIMEOUT)(_ ==> FileWatchEvent(f, Modify))
        }
      }
      "handle file modify events" - withTempFile { f =>
        val events = new ArrayBlockingQueue[FileWatchEvent](10)
        val callback: Callback = e => if (!e.path.isDirectory && e.kind != Create) events.add(e)
        usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, fileFlags)(callback)) { w =>
          w.register(f.getParent)
          f.write("hello")
          events.poll(DEFAULT_TIMEOUT)(_ ==> FileWatchEvent(f, Modify))
        }
      }
      "handle file deletion events" - withTempFile { f =>
        val events = new ArrayBlockingQueue[FileWatchEvent](10)
        val callback: Callback = e => { if (!e.path.exists && e.kind != Create) events.add(e) }
        usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, fileFlags)(callback)) { w =>
          w.register(f.getParent)
          f.delete()
          events.poll(DEFAULT_TIMEOUT)(_ ==> FileWatchEvent(f, Delete))
        }
      }
      "remove redundant watchers" - withTempDirectory { dir =>
        val events = new ArrayBlockingQueue[String](10)
        val callback: String => Unit = e => { events.add(e) }
        withTempDirectory(dir) { subdir =>
          usingAsync(AppleDirectoryWatcher(DEFAULT_LATENCY, fileFlags)(_ => {}, callback)) { w =>
            w.register(subdir)
            w.register(dir)
            events.poll(DEFAULT_TIMEOUT)(_ ==> subdir.fullName)
          }
        }
      }
    }
  }
}
