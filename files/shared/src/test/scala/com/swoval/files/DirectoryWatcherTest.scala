package com.swoval.files

import java.nio.file.attribute.FileTime
import java.util.concurrent.{ TimeUnit, TimeoutException }
import java.nio.file.{ Files => JFiles }

import com.swoval.files.apple.AppleDirectoryWatcher.OnStreamRemoved
import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.DirectoryWatcher.Event.{ Create, Delete, Modify }
import com.swoval.files.apple.{ AppleDirectoryWatcher, Flags }
import com.swoval.files.test.{ ArrayBlockingQueue, _ }
import com.swoval.test._
import utest._
import Implicits.executionContext

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
          withTempDirectory(dir) { subdir =>
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
      'unregister - withTempDirectory { dir =>
        val firstLatch = new CountDownLatch(1)
        val secondLatch = new CountDownLatch(2)
        val callback: Callback = (e: DirectoryWatcher.Event) => {
          if (e.path.endsWith("file")) {
            firstLatch.countDown()
            secondLatch.countDown()
          }
        }
        import Implicits.executionContext
        usingAsync(defaultWatcher(DEFAULT_LATENCY, fileFlags, callback)) { c =>
          c.register(dir)
          val file = JFiles.createFile(dir.resolve("file"))
          firstLatch
            .waitFor(DEFAULT_TIMEOUT) {
              assert(JFiles.exists(file))
            }
            .flatMap { _ =>
              c.unregister(dir)
              JFiles.delete(file)
              secondLatch
                .waitFor(5.millis) {
                  throw new IllegalStateException(
                    "Watcher triggered for path no longer under monitoring")
                }
                .recover {
                  case _: TimeoutException => ()
                }
            }
        }
      }
    }
    'depth - {
      'limit - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val callback: Callback =
            (e: DirectoryWatcher.Event) => if (e.path.endsWith("foo")) events.add(e)
          usingAsync(defaultWatcher(DEFAULT_LATENCY, fileFlags, callback)) { w =>
            w.register(dir, 0)
            val file = subdir.resolve(Path("foo")).createFile()
            events
              .poll(10.milliseconds) { _ =>
                throw new IllegalStateException(
                  "Event triggered for file that shouldn't be monitored")
              }
              .recoverWith {
                case _: TimeoutException =>
                  w.register(dir, 1)
                  JFiles.setLastModifiedTime(file, FileTime.fromMillis(3000))
                  events.poll(DEFAULT_TIMEOUT) { e =>
                    e.path ==> file
                    e.path.lastModified ==> 3000
                  }

              }
          }
        }
      }
    }
  }
}
