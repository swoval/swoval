package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.test._
import com.swoval.test._
import utest._

object NioDirectoryWatcherTest extends TestSuite {
  val tests = testOn(Linux) {

    implicit val latch: com.swoval.files.test.CountDownLatch = new CountDownLatch(1)
    'onCreate - withTempDirectory { dir =>
      val events = new ArrayBlockingQueue[FileWatchEvent](10)
      val callback: Callback = e => events.add(e)

      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        w.register(dir)
        val f = dir.relativize(Path("foo")).createFile()
        events.poll(DEFAULT_TIMEOUT)(_.path === f)
      }
    }
    'onModify - withTempDirectory { dir =>
      val events = new ArrayBlockingQueue[FileWatchEvent](10)
      val callback: Callback = e => events.add(e)

      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        val f = dir.relativize(Path("foo")).createFile()
        w.register(dir)
        f.setLastModifiedTime(0L)
        events.poll(DEFAULT_TIMEOUT)(_.path === f)
      }
    }
    'onDelete - withTempDirectory { dir =>
      val events = new ArrayBlockingQueue[FileWatchEvent](10)
      val callback: Callback = e => events.add(e)

      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        val f = dir.relativize(Path("foo")).createFile()
        w.register(dir)
        f.delete()
        events.poll(DEFAULT_TIMEOUT)(_.path === f)
      }
    }
    'subdirectories - {
      'onCreate - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val events = new ArrayBlockingQueue[FileWatchEvent](10)
          val callback: Callback = e => if (e.path != dir) events.add(e)

          usingAsync(new NioDirectoryWatcher(callback)) { w =>
            w.register(dir)
            val f = dir.relativize(Path("foo")).createFile()
            events.poll(DEFAULT_TIMEOUT)(_.path === f)
          }
        }
      }
    }
  }
}
