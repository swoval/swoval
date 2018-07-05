package com.swoval.files

import java.nio.file.Paths

import com.swoval.files.apple.Flags
import com.swoval.files.test._
import com.swoval.functional.Consumer
import com.swoval.test._

import scala.concurrent.duration._
import utest._

object ApplePathWatcherTest extends TestSuite {
  import DefaultPathWatcherTest.defaultWatcher
  val DEFAULT_LATENCY = 5.milliseconds
  val dirFlags = new Flags.Create().setNoDefer()
  val tests = testOn(MacOS) {
    val events = new ArrayBlockingQueue[PathWatcher.Event](10)
    val dirFlags = ApplePathWatcher.Options.flags(new Flags.Create().setNoDefer());
    "directories" - {
      'onCreate - {
        withTempDirectory { dir =>
          assert(dir.exists)
          val callback: Consumer[PathWatcher.Event] =
            (e: PathWatcher.Event) => events.add(e)

          usingAsync(defaultWatcher(callback, dirFlags)) { w =>
            w.register(dir)
            val f = dir.resolve(Paths.get("foo")).createFile()
            events.poll(DEFAULT_TIMEOUT)(_.path === dir)
          }
        }
      }
      'onModify - withTempDirectory { dir =>
        val callback: Consumer[PathWatcher.Event] =
          (e: PathWatcher.Event) => events.add(e)

        usingAsync(defaultWatcher(callback, dirFlags)) { w =>
          val f = dir.resolve(Paths.get("foo")).createFile()
          w.register(dir)
          f.setLastModifiedTime(0L)
          f.delete()
          f.createFile()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'onDelete - withTempDirectory { dir =>
        val callback: Consumer[PathWatcher.Event] =
          (e: PathWatcher.Event) => events.add(e)

        usingAsync(defaultWatcher(callback, dirFlags)) { w =>
          val f = dir.resolve(Paths.get("foo")).createFile()
          w.register(dir)
          f.delete()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'subdirectories - {
        'onCreate - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val callback: Consumer[PathWatcher.Event] =
              (e: PathWatcher.Event) => if (e.path != dir) events.add(e)

            usingAsync(defaultWatcher(callback, dirFlags)) { w =>
              w.register(dir)
              subdir.resolve(Paths.get("foo")).createFile()
              events.poll(DEFAULT_TIMEOUT)(_.path ==> subdir)
            }
          }
        }
      }
    }
  }
}