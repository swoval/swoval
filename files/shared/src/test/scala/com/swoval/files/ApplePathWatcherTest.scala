package com.swoval.files

import java.io.IOException
import java.nio.file.{ Path, Paths }
import java.util.concurrent.{ ConcurrentHashMap, TimeUnit }

import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatchers.Event
import com.swoval.files.TestHelpers._
import com.swoval.files.apple.Flags
import com.swoval.files.test._
import com.swoval.functional
import com.swoval.test._
import utest._

import scala.concurrent.duration._
import scala.collection.JavaConverters._
object ApplePathWatcherTest extends TestSuite {
  val DEFAULT_LATENCY = 5.milliseconds
  val dirFlags = new Flags.Create().setNoDefer()
  def defaultWatcher(callback: PathWatchers.Event => _): PathWatcher[PathWatchers.Event] = {
    val watcher = new ApplePathWatcher(
      10,
      TimeUnit.MILLISECONDS,
      dirFlags,
      (_: String) => {},
      new DirectoryRegistryImpl
    )
    watcher.addObserver(callback)
    watcher
  }
  val tests = testOn(MacOS) {
    val events = new ArrayBlockingQueue[Event](10)
    val dirFlags = new Flags.Create().setNoDefer()
    "directories" - {
      'onCreate - {
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory { dir =>
          assert(dir.exists)
          val callback = (e: Event) => events.add(e)

          usingAsync(defaultWatcher(callback)) { w =>
            w.register(dir)
            val f = dir.resolve(Paths.get("foo")).createFile()
            events.poll(DEFAULT_TIMEOUT)(_.getTypedPath.getPath === dir)
          }
        }
      }
      'onModify - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val callback = (e: Event) => events.add(e)

        usingAsync(defaultWatcher(callback)) { w =>
          val f = dir.resolve(Paths.get("foo")).createFile()
          w.register(dir)
          f.setLastModifiedTime(0L)
          f.delete()
          f.createFile()
          events.poll(DEFAULT_TIMEOUT)(_.getTypedPath.getPath === dir)
        }
      }
      'onDelete - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val callback = (e: Event) => events.add(e)

        usingAsync(defaultWatcher(callback)) { w =>
          val f = dir.resolve(Paths.get("foo")).createFile()
          w.register(dir)
          f.delete()
          events.poll(DEFAULT_TIMEOUT)(_.getTypedPath.getPath === dir)
        }
      }
      'subdirectories - {
        'onCreate - withTempDirectory { dir =>
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory(dir) { subdir =>
            val callback = (e: Event) => if (e.getTypedPath.getPath != dir) events.add(e)

            usingAsync(defaultWatcher(callback)) { w =>
              w.register(dir)
              subdir.resolve(Paths.get("foo")).createFile()
              events.poll(DEFAULT_TIMEOUT)(_.getTypedPath.getPath ==> subdir)
            }
          }
        }
      }
    }
  }
}
