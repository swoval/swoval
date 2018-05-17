package com.swoval.files

import java.nio.file.{ Path => JPath }

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.test._
import com.swoval.test._
import utest._

import scala.concurrent.Future

object NioDirectoryWatcherTest extends TestSuite {
  // I am pretty sure there is a bug in libuv apple file system event implementation that
  // can cause events to be dropped. This is why I exclude the test from running on osx on scala.js.
  val tests = if (!((System.getProperty("java.vm.name") == "Scala.js") && Platform.isMac)) Tests {
    val events = new ArrayBlockingQueue[DirectoryWatcher.Event](1)
    implicit val latch: com.swoval.files.test.CountDownLatch = new CountDownLatch(1)
    def check(file: JPath)(f: JPath => Unit): Future[Unit] = events.poll(DEFAULT_TIMEOUT) { e =>
      e.path ==> file
      f(e.path)
    }
    'onCreate - withTempDirectory { dir =>
      val f = dir.resolve(Path("create"))
      val callback: Callback = (e: DirectoryWatcher.Event) => if (e.path == f) events.add(e)
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        w.register(dir)
        f.createFile()
        check(f)(_.exists)
      }
    }
    'onModify - withTempDirectory { dir =>
      val f = dir.resolve(Path("modify"))
      val callback: Callback = (e: DirectoryWatcher.Event) => if (e.path == f) events.add(e)
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        f.createFile()
        w.register(dir)
        f.setLastModifiedTime(0L)
        check(f)(_.lastModified ==> 0)
      }
    }
    'onDelete - withTempDirectory { dir =>
      val f = dir.resolve(Path("delete"))
      val callback: Callback = (e: DirectoryWatcher.Event) => if (e.path == f) events.add(e)
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        f.createFile()
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
      'ignore - { println("Not running NioDirectoryWatcherTest on scala.js for Mac OS") }
    }
}
