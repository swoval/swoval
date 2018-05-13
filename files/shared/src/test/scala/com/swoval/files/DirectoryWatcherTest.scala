package com.swoval.files

import java.util.concurrent.TimeUnit

import com.swoval.files.AppleDirectoryWatcher.OnStreamRemoved
import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.DirectoryWatcher.Event.{ Create, Delete, Modify }
import com.swoval.files.apple.Flags
import com.swoval.files.test.{ ArrayBlockingQueue, _ }
import com.swoval.test._
import utest._

import scala.concurrent.Future
import scala.concurrent.duration._

object DirectoryWatcherTest extends TestSuite {
  type Event = DirectoryWatcher.Event
  val DEFAULT_LATENCY = 5.milliseconds
  val fileFlags = new Flags.Create().setNoDefer().setFileEvents()
  def defaultWatcher(latency: FiniteDuration,
                     flags: Flags.Create,
                     callback: Callback): DirectoryWatcher =
    DirectoryWatcher.defaultWatcher(latency.toNanos, TimeUnit.NANOSECONDS, flags, callback)
  val tests = Tests {
    val events = new ArrayBlockingQueue[DirectoryWatcher.Event](10)
    'files - {
      "handle file creation events" - withTempDirectory { dir =>
        val callback: Callback = (e: DirectoryWatcher.Event) => {
          if (!e.path.isDirectory) events.add(e)
        }

        usingAsync(defaultWatcher(DEFAULT_LATENCY, fileFlags, callback)) { w =>
          w.register(dir)
          dir.resolve(Path("foo")).createFile()
          val expected =
            if (System.getProperty("java.vm.name") == "Scala.js" && !Platform.isMac) Modify
            else Create
          events.poll(DEFAULT_TIMEOUT)(_.kind ==> expected)
        }
      }
      "handle file touch events" - withTempFile { f =>
        val callback: Callback =
          (e: DirectoryWatcher.Event) => if (!e.path.isDirectory && e.kind != Create) events.add(e)
        usingAsync(defaultWatcher(DEFAULT_LATENCY, fileFlags, callback)) { w =>
          w.register(f.getParent)
          f.setLastModifiedTime(0L)
          events.poll(DEFAULT_TIMEOUT)(_ ==> new Event(f, Modify))
        }
      }
      "handle file modify events" - withTempFile { f =>
        val callback: Callback =
          (e: DirectoryWatcher.Event) => if (!e.path.isDirectory && e.kind != Create) events.add(e)
        usingAsync(defaultWatcher(DEFAULT_LATENCY, fileFlags, callback)) { w =>
          w.register(f.getParent)
          f.write("hello")
          events.poll(DEFAULT_TIMEOUT)(_ ==> new Event(f, Modify))
        }
      }
      "handle file deletion events" - withTempFile { f =>
        val callback: Callback = (e: DirectoryWatcher.Event) => {
          if (!e.path.exists && e.kind != Create && e.path == f)
            events.add(e)
        }
        usingAsync(defaultWatcher(DEFAULT_LATENCY, fileFlags, callback)) { w =>
          w.register(f.getParent)
          f.delete()
          events.poll(DEFAULT_TIMEOUT) { e =>
            e ==> new Event(f, Delete)
          }
        }
      }
      "remove redundant watchers" - withTempDirectory { dir =>
        if (Platform.isMac) {
          val events = new ArrayBlockingQueue[String](10)
          val callback: OnStreamRemoved = new OnStreamRemoved {
            override def apply(stream: String): Unit = events.add(stream)
          }
          withTempDirectorySync(dir) { subdir =>
            val watcher = new AppleDirectoryWatcher(DEFAULT_LATENCY.toNanos / 1.0e9,
                                                    fileFlags,
                                                    Executor.make("apple-directory-watcher-test"),
                                                    (_: DirectoryWatcher.Event) => {},
                                                    callback);
            usingAsync(watcher) { w =>
              w.register(subdir)
              w.register(dir)
              events.poll(DEFAULT_TIMEOUT)(_ ==> subdir.toString)
            }
          }
        } else {
          Future.successful(())
        }
      }
    }
  }
}
