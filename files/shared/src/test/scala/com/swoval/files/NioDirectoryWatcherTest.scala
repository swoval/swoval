package com.swoval.files

import java.nio.file.{ Path => JPath }

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.test._
import com.swoval.test._
import utest._

object NioDirectoryWatcherTest extends TestSuite {
  val tests = if (!Platform.isMac) Tests {
    implicit val latch: com.swoval.files.test.CountDownLatch = new CountDownLatch(1)
    val events = new ArrayBlockingQueue[DirectoryWatcher.Event](10)
    val callback: Callback = (e: DirectoryWatcher.Event) => events.add(e)
    def check(file: JPath)(f: JPath => Unit) = events.poll(DEFAULT_TIMEOUT) { e =>
      e.path ==> file
      f(e.path)
    }
    'onCreate - withTempDirectory { dir =>
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        w.register(dir)
        val f = dir.resolve(Path("foo")).createFile()
        check(f)(_.exists)
      }
    }
    'onModify - withTempDirectory { dir =>
      val f = dir.resolve(Path("foo"))
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        f.createFile()
        w.register(dir)
        f.setLastModifiedTime(0L)
        check(f)(_.lastModified ==> 0)
      }
    }
    'onDelete - withTempDirectory { dir =>
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        val f = dir.resolve(Path("foo")).createFile()
        w.register(dir)
        f.delete()
        check(f)(p => assert(!p.exists))
      }
    }
    'subdirectories - {
      'onCreate - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val callback: Callback =
            (e: DirectoryWatcher.Event) => if (e.path != dir && !e.path.isDirectory) events.add(e)
          usingAsync(new NioDirectoryWatcher(callback)) { w =>
            w.register(dir)
            val f = subdir.resolve(Path("foo")).createFile()
            check(f)(p => assert(p.exists))
          }
        }
      }
    }
  } else
    Tests {
      'ignore - { println("Not running NioDirectoryWatcherTest on the JVM for Mac OS") }
    }
}
