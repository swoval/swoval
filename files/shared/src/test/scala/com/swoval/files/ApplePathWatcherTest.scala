package com.swoval.files

import java.nio.file.Paths

import com.swoval.files.PathWatchers.Event
import com.swoval.files.apple.Flags
import com.swoval.files.test._
import com.swoval.functional.Consumer
import com.swoval.test._

import scala.concurrent.duration._
import utest._

object ApplePathWatcherTest extends TestSuite {
  val DEFAULT_LATENCY = 5.milliseconds
  val dirFlags = new Flags.Create().setNoDefer()
  def defaultWatcher(callback: Consumer[PathWatchers.Event]): PathWatcher =
    new ApplePathWatcher(
      0.01,
      dirFlags,
      Executor.make("ApplePathWatcher-callback-executor"),
      callback,
      (s: String) => {},
      Executor.make("ApplePathWatcher-internal-executor"),
      new DirectoryRegistry
    )
  val tests = testOn(MacOS) {
    val events = new ArrayBlockingQueue[Event](10)
    val dirFlags = new Flags.Create().setNoDefer()
    "directories" - {
      'onCreate - {
        withTempDirectory { dir =>
          assert(dir.exists)
          val callback: Consumer[Event] =
            (e: Event) => events.add(e)

          usingAsync(defaultWatcher(callback)) { w =>
            w.register(dir)
            val f = dir.resolve(Paths.get("foo")).createFile()
            events.poll(DEFAULT_TIMEOUT)(_.path === dir)
          }
        }
      }
      'onModify - withTempDirectory { dir =>
        val callback: Consumer[Event] =
          (e: Event) => events.add(e)

        usingAsync(defaultWatcher(callback)) { w =>
          val f = dir.resolve(Paths.get("foo")).createFile()
          w.register(dir)
          f.setLastModifiedTime(0L)
          f.delete()
          f.createFile()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'onDelete - withTempDirectory { dir =>
        val callback: Consumer[Event] =
          (e: Event) => events.add(e)

        usingAsync(defaultWatcher(callback)) { w =>
          val f = dir.resolve(Paths.get("foo")).createFile()
          w.register(dir)
          f.delete()
          events.poll(DEFAULT_TIMEOUT)(_.path === dir)
        }
      }
      'subdirectories - {
        'onCreate - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            val callback: Consumer[Event] =
              (e: Event) => if (e.path != dir) events.add(e)

            usingAsync(defaultWatcher(callback)) { w =>
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
