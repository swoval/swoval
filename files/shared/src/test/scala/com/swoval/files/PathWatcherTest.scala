package com.swoval.files

import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, Paths }
import java.util.concurrent.{ TimeUnit, TimeoutException }

import PathWatcher.Event.{ Create, Delete, Modify }
import com.swoval.files.apple.Flags
import com.swoval.files.test.{ ArrayBlockingQueue, _ }
import com.swoval.functional.Consumer
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.concurrent.Future
import scala.concurrent.duration._

trait PathWatcherTest extends TestSuite {
  type Event = PathWatcher.Event
  val DEFAULT_LATENCY = 5.milliseconds
  val fileFlags = new Flags.Create().setNoDefer().setFileEvents()
  def defaultWatcher(callback: Consumer[PathWatcher.Event],
                     options: PathWatcher.Option*): PathWatcher
  val testsImpl = Tests {
    val events = new ArrayBlockingQueue[PathWatcher.Event](10)
    'files - {
      'onCreate - withTempDirectory { dir =>
        val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
          if (e.path.endsWith("foo")) events.add(e)
        }

        usingAsync(defaultWatcher(callback)) { w =>
          w.register(dir)
          val file = dir.resolve(Paths.get("foo")).createFile()
          events.poll(DEFAULT_TIMEOUT)(_.path ==> file)
        }
      }
      'onTouch - withTempFile { f =>
        val callback: Consumer[PathWatcher.Event] =
          (e: PathWatcher.Event) => if (e.path == f && e.kind != Create) events.add(e)
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f.setLastModifiedTime(0L)
          events.poll(DEFAULT_TIMEOUT)(_ ==> new Event(f, Modify))
        }
      }
      'onModify - withTempFile { f =>
        val callback: Consumer[PathWatcher.Event] =
          (e: PathWatcher.Event) => if (e.path == f && e.kind != Create) events.add(e)
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f.write("hello")
          events.poll(DEFAULT_TIMEOUT)(_.path ==> f)
        }
      }
      'onDelete - withTempFile { f =>
        val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
          if (!e.path.exists && e.kind == Delete && e.path == f)
            events.add(e)
        }
        usingAsync(defaultWatcher(callback)) { w =>
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
          val callback: Consumer[String] = (stream: String) => events.add(stream)
          withTempDirectory(dir) { subdir =>
            val watcher = new ApplePathWatcher(
              DEFAULT_LATENCY.toNanos / 1.0e9,
              fileFlags,
              Executor.make("apple-directory-watcher-test"),
              (_: PathWatcher.Event) => {},
              callback,
              null,
              new DirectoryRegistry
            )
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
        val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
          if (e.path.endsWith("foo")) {
            firstLatch.countDown()
          } else if (e.path.endsWith("bar")) {
            secondLatch.countDown()
          }
        }
        import Implicits.executionContext
        usingAsync(defaultWatcher(callback)) { c =>
          c.register(dir)
          val file = dir.resolve("foo").createFile()
          firstLatch
            .waitFor(DEFAULT_TIMEOUT) {
              assert(Files.exists(file))
            }
            .flatMap { _ =>
              c.unregister(dir)
              dir.resolve("bar").createFile()
              secondLatch
                .waitFor(100.millis) {
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
    'register - {
      'file - withTempFile { file =>
        val latch = new CountDownLatch(1)
        val callback: Consumer[PathWatcher.Event] = (_: PathWatcher.Event) => {
          latch.countDown()
        }

        usingAsync(defaultWatcher(callback)) { w =>
          w.register(file)
          file.setLastModifiedTime(3000)
          latch.waitFor(DEFAULT_TIMEOUT) {
            file.lastModified ==> 3000
          }
        }

      }
      'change - withTempFile { file =>
        val dirLatch = new CountDownLatch(1)
        val fileLatch = new CountDownLatch(1)
        val subfile = file.resolve("subfile")
        val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
          if (e.path == file) dirLatch.countDown()
          else if (e.path == subfile) fileLatch.countDown()
        }

        usingAsync(defaultWatcher(callback, PathWatcher.Options.POLL_NEW_DIRECTORIES)) { w =>
          w.register(file)
          file.setLastModifiedTime(3000)
          dirLatch
            .waitFor(DEFAULT_TIMEOUT) {
              file.lastModified ==> 3000
              file.delete()
              Files.createDirectory(file)
              subfile.createFile()
            }
            .flatMap { _ =>
              fileLatch.waitFor(DEFAULT_TIMEOUT) {
                assert(subfile.exists)
              }
            }
        }

      }
      'absent - {
        'initially - withTempDirectory { dir =>
          val dirLatch = new CountDownLatch(1)
          val fileLatch = new CountDownLatch(1)
          val subdir = dir.resolve("subdir")
          val file = subdir.resolve("file")
          val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
            if (e.path == subdir && Files.exists(subdir)) dirLatch.countDown()
            else if (e.path == file && Files.exists(file)) fileLatch.countDown()
          }

          usingAsync(defaultWatcher(callback)) { w =>
            w.register(subdir)
            Files.createDirectory(subdir)
            dirLatch
              .waitFor(DEFAULT_TIMEOUT) {
                assert(Files.exists(subdir))
                file.createFile()
              }
              .flatMap { _ =>
                fileLatch.waitFor(DEFAULT_TIMEOUT) {
                  assert(Files.exists(file))
                }
              }
          }
        }
        'afterRemoval - {}
      }
    }
    'depth - {
      'limit - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val callback: Consumer[PathWatcher.Event] =
            (e: PathWatcher.Event) => if (e.path.endsWith("foo")) events.add(e)
          usingAsync(defaultWatcher(callback)) { w =>
            w.register(dir, 0)
            val file = subdir.resolve(Paths.get("foo")).createFile()
            events
              .poll(100.milliseconds) { _ =>
                throw new IllegalStateException(
                  s"Event triggered for file that shouldn't be monitored $file")
              }
              .recoverWith {
                case _: TimeoutException =>
                  w.register(dir, 1)
                  file.setLastModifiedTime(3000)
                  events.poll(DEFAULT_TIMEOUT) { e =>
                    e.path ==> file
                    e.path.lastModified ==> 3000
                  }

              }
          }
        }
      }
      'holes - {
        'connect - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { secondSubdir =>
              withTempDirectory(secondSubdir) { thirdSubdir =>
                val subdirEvents = new ArrayBlockingQueue[PathWatcher.Event](1)
                val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
                  if (e.path.endsWith("foo")) events.add(e)
                  if (e.path.endsWith("bar")) subdirEvents.add(e)
                }
                usingAsync(defaultWatcher(callback)) { w =>
                  w.register(dir, 0)
                  w.register(secondSubdir, 0)
                  val file = thirdSubdir.resolve("foo").createFile()
                  events
                    .poll(100.milliseconds) { _ =>
                      throw new IllegalStateException(
                        s"Event triggered for file that shouldn't be monitored $file")
                    }
                    .recoverWith {
                      case _: TimeoutException =>
                        w.register(dir, 3)
                        file.setLastModifiedTime(3000)
                        events
                          .poll(DEFAULT_TIMEOUT) { e =>
                            e.path ==> file
                            e.path.lastModified ==> 3000
                          }
                          .flatMap { _ =>
                            val subdirFile = subdir.resolve("bar").createFile()
                            subdirEvents.poll(DEFAULT_TIMEOUT) { e =>
                              e.path ==> subdirFile
                            }
                          }
                    }
                }
              }
            }
          }
        }
        'extend - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { secondSubdir =>
              withTempDirectory(secondSubdir) { thirdSubdir =>
                val subdirEvents = new ArrayBlockingQueue[PathWatcher.Event](1)
                val callback: Consumer[PathWatcher.Event] = (e: PathWatcher.Event) => {
                  if (e.path.endsWith("foo")) events.add(e)
                  if (e.path.endsWith("bar")) subdirEvents.add(e)
                }
                usingAsync(defaultWatcher(callback)) { w =>
                  w.register(dir, 0)
                  w.register(secondSubdir, 1)
                  val file = subdir.resolve("foo").createFile()
                  events
                    .poll(100.milliseconds) { _ =>
                      throw new IllegalStateException(
                        s"Event triggered for file that shouldn't be monitored $file")
                    }
                    .recoverWith {
                      case _: TimeoutException =>
                        w.register(dir, 2)
                        Files.setLastModifiedTime(file, FileTime.fromMillis(3000))
                        events
                          .poll(DEFAULT_TIMEOUT) { e =>
                            e.path ==> file
                            e.path.lastModified ==> 3000
                          }
                          .flatMap { _ =>
                            val subdirFile = subdir.resolve("bar").createFile()
                            subdirEvents.poll(DEFAULT_TIMEOUT) { e =>
                              e.path ==> subdirFile
                            }
                          }
                    }
                }
              }
            }
          }
        }
      }
    }
  }
}
object DefaultPathWatcherTest extends PathWatcherTest {
  val tests = testsImpl
  def defaultWatcher(callback: Consumer[PathWatcher.Event],
                     options: PathWatcher.Option*): PathWatcher =
    PathWatcher.defaultWatcher(callback, Executor.make("DirectoryWatcherTestExecutor"), options: _*)
}
object NioPathWatcherTest extends PathWatcherTest {
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests {
        'ignore - {
          println("Not running NioDirectoryWatcherTest on platform other than osx on the jvm")
        }
      }
  def defaultWatcher(callback: Consumer[PathWatcher.Event],
                     options: PathWatcher.Option*): PathWatcher =
    PlatformWatcher.make(callback,
                         Executor.make("NioDirectoryWatcherTestExecutor"),
                         new DirectoryRegistry(),
                         options: _*)
}
