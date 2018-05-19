package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.apple.Flags
import com.swoval.files.test._
import com.swoval.test._

import scala.concurrent.duration._
import utest._

object AppleDirectoryWatcherTest extends TestSuite {
  import DirectoryWatcherTest.defaultWatcher
  val DEFAULT_LATENCY = 5.milliseconds
  val dirFlags = new Flags.Create().setNoDefer()
  val tests = testOn(MacOS) {
    val events = new ArrayBlockingQueue[DirectoryWatcher.Event](10)
    "directories" - {
      'onCreate - {
        withTempDirectory { dir =>
          assert(dir.exists)
          val callback: Callback = (e: DirectoryWatcher.Event) => events.add(e)

          usingAsync(defaultWatcher(DEFAULT_LATENCY, dirFlags, callback)) { w =>
            w.register(dir)
            val f = dir.resolve(Path("foo")).createFile()
            events.poll(DEFAULT_TIMEOUT)(_.path === dir)
          }
        }
      }
      'onModify - withTempDirectory { dir =>
        val callback: Callback = (e: DirectoryWatcher.Event) => events.add(e)

        usingAsync(defaultWatcher(DEFAULT_LATENCY, dirFlags, callback)) { w =>
          val f = dir.resolve(Path("foo")).createFile()
          w.register(dir)
          f.setLastModifiedTime(0L)
          f.delete()
          f.createFile()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'onDelete - withTempDirectory { dir =>
        val callback: Callback = (e: DirectoryWatcher.Event) => events.add(e)

        usingAsync(defaultWatcher(DEFAULT_LATENCY, dirFlags, callback)) { w =>
          val f = dir.resolve(Path("foo")).createFile()
          w.register(dir)
          f.delete()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'subdirectories - {
        'onCreate - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val callback: Callback =
              (e: DirectoryWatcher.Event) => if (e.path != dir) events.add(e)

            usingAsync(defaultWatcher(DEFAULT_LATENCY, dirFlags, callback)) { w =>
              w.register(dir)
              subdir.resolve(Path("foo")).createFile()
              events.poll(DEFAULT_TIMEOUT)(_.path ==> subdir)
            }
          }
        }
      }
    }
  }
}
