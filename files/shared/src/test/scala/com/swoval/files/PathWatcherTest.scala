package com.swoval
package files

import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.{ TimeUnit, TimeoutException }

import com.swoval.files.Executor.ThreadHandle
import com.swoval.files.PathWatchers.Event.Kind
import com.swoval.files.PathWatchers.Event.Kind.{ Create, Delete, Modify }
import com.swoval.files.apple.Flags
import com.swoval.files.test.{ ArrayBlockingQueue, _ }
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

import TestHelpers._

trait PathWatcherTest extends TestSuite {
  type Event = PathWatchers.Event
  val DEFAULT_LATENCY = 5.milliseconds
  val fileFlags = new Flags.Create().setNoDefer().setFileEvents()

  def defaultWatcher(callback: PathWatchers.Event => _): PathWatcher[PathWatchers.Event]
  def expectedDeletions(dir: Path, subdir: Path): Set[Path]

  val testsImpl = Tests {
    val events = new ArrayBlockingQueue[PathWatchers.Event](10)
    'files - {
      'onCreate - withTempDirectory { dir =>
        val callback = (e: PathWatchers.Event) => {
          if (e.getPath.endsWith("foo")) events.add(e)
        }

        usingAsync(defaultWatcher(callback)) { w =>
          w.register(dir)
          val file = dir.resolve(Paths.get("foo")).createFile()
          events.poll(DEFAULT_TIMEOUT)(_.getPath ==> file)
        }
      }
      'onTouch - withTempFile { f =>
        val callback =
          (e: PathWatchers.Event) => if (e.getPath == f && e.getKind != Create) events.add(e)
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f.setLastModifiedTime(0L)
          events.poll(DEFAULT_TIMEOUT)(_ ==> new Event(TypedPaths.get(f), Modify))
        }
      }
      'onModify - withTempFile { f =>
        val callback =
          (e: PathWatchers.Event) => if (e.getPath == f && e.getKind != Create) events.add(e)
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f.write("hello")
          events.poll(DEFAULT_TIMEOUT)(_.getPath ==> f)
        }
      }
      'onDelete - withTempFile { f =>
        val callback = (e: PathWatchers.Event) => {
          if (!e.getPath.exists && e.getKind == Delete && e.getPath == f)
            events.add(e)
        }
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f.delete()
          events.poll(DEFAULT_TIMEOUT) { e =>
            e ==> new Event(TypedPaths.get(f), Delete)
          }
        }
      }
      'redundant - withTempDirectory { dir =>
        if (Platform.isMac) {
          val events = new ArrayBlockingQueue[String](10)
          val callback: BiConsumer[String, ThreadHandle] =
            (stream: String, _: ThreadHandle) => events.add(stream)
          withTempDirectory(dir) { subdir =>
            val watcher = new ApplePathWatcher(
              DEFAULT_LATENCY.toNanos,
              TimeUnit.NANOSECONDS,
              fileFlags,
              callback,
              null,
              new DirectoryRegistryImpl
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
      'unregister - withTempDirectory { root =>
        val dir = Files.createDirectory(root.resolve("unregister"))
        val firstLatch = new CountDownLatch(1)
        val secondLatch = new CountDownLatch(2)
        val callback = (e: PathWatchers.Event) => {
          if (e.getPath.endsWith("foo")) {
            firstLatch.countDown()
          } else if (e.getPath.endsWith("bar")) {
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
        val callback = (_: PathWatchers.Event) => latch.countDown()

        usingAsync(defaultWatcher(callback)) { w =>
          w.register(file)
          file.setLastModifiedTime(3000)
          latch.waitFor(DEFAULT_TIMEOUT) {
            file.lastModified ==> 3000
          }
        }

      }
      'change - withTempDirectory { root =>
        val dir = Files.createDirectories(root.resolve("change").resolve("debug"))
        val file = dir.resolve("file").createFile()
        val dirLatch = new CountDownLatch(1)
        val fileLatch = new CountDownLatch(1)
        val subfile = file.resolve("subfile")
        val callback = (e: PathWatchers.Event) => {
          if (e.getPath == file) dirLatch.countDown()
          else if (e.getPath == subfile) fileLatch.countDown()
        }

        usingAsync(defaultWatcher(callback)) { w =>
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
        'initially - withTempDirectory { root =>
          val dir = Files.createDirectories(root.resolve("initial").resolve("debug"))
          val dirLatch = new CountDownLatch(1)
          val fileLatch = new CountDownLatch(1)
          val subdir = dir.resolve("subdir")
          val file = subdir.resolve("file-initial")
          val callback = (e: PathWatchers.Event) => {
            if (e.getPath == subdir && e.exists) dirLatch.countDown()
            else if (e.getPath == file && e.exists) fileLatch.countDown()
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
          }.andThen {
            case Success(_) =>
            case Failure(_) => System.out.println(s"absent.initially failed for $file")
          }
        }
      }
    }
    'directory - {
      'delete - (if (Platform.isJVM || !Platform.isWin) {
                   withTempDirectory { dir =>
                     withTempDirectory(dir) { subdir =>
                       val deletions = mutable.Set.empty[Path]
                       val expected = expectedDeletions(dir, subdir)
                       val deletionLatch = new CountDownLatch(expected.size)
                       val creationLatch = new CountDownLatch(1)
                       var creationPending = false
                       val callback = (e: PathWatchers.Event) => {
                         if (e.getKind == Kind.Delete && deletions.add(e.getPath)) {
                           deletionLatch.countDown()
                         }
                         if (e.getPath.equals(dir) && creationPending) {
                           creationPending = false
                           creationLatch.countDown()
                         }
                       }
                       usingAsync(defaultWatcher(callback)) { w =>
                         w.register(dir, Integer.MAX_VALUE)
                         dir.deleteRecursive()
                         deletionLatch
                           .waitFor(DEFAULT_TIMEOUT) {
                             deletions.toSet === expected
                             creationPending = true
                             Files.createDirectory(dir)
                           }
                           .flatMap { _ =>
                             creationLatch.waitFor(DEFAULT_TIMEOUT) {}
                           }
                       }
                     }
                   }
                 } else {
                   Future.successful(
                     println("not running directory.delete test on scala.js on windows"))
                 })
    }
    'depth - {
      'limit - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val callback = (e: PathWatchers.Event) => if (e.getPath.endsWith("foo")) events.add(e)
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
                    e.getPath ==> file
                    e.getPath.lastModified ==> 3000
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
                val subdirEvents = new ArrayBlockingQueue[PathWatchers.Event](1)
                val callback = (e: PathWatchers.Event) => {
                  if (e.getPath.endsWith("foo")) events.add(e)
                  if (e.getPath.endsWith("bar")) subdirEvents.add(e)
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
                            e.getPath ==> file
                            e.getPath.lastModified ==> 3000
                          }
                          .flatMap { _ =>
                            val subdirFile = subdir.resolve("bar").createFile()
                            subdirEvents.poll(DEFAULT_TIMEOUT) { e =>
                              e.getPath ==> subdirFile
                            }
                          }
                    }
                }
              }
            }
          }
        }
        'extend - withTempDirectory { root =>
          val dir = Files.createDirectories(root.resolve("extend").resolve("debug"))
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { secondSubdir =>
              withTempDirectory(secondSubdir) { thirdSubdir =>
                val subdirEvents = new ArrayBlockingQueue[PathWatchers.Event](1)
                val callback = (e: PathWatchers.Event) => {
                  if (e.getPath.endsWith("foo")) events.add(e)
                  if (e.getPath.endsWith("bar")) subdirEvents.add(e)
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
                        val files = events.poll(DEFAULT_TIMEOUT) { e =>
                          e.getPath ==> file
                          e.getPath.lastModified ==> 3000
                        }
                        files.flatMap { _ =>
                          val subdirFile = subdir.resolve("bar").createFile()
                          subdirEvents.poll(DEFAULT_TIMEOUT) { e =>
                            e.getPath ==> subdirFile
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

object PathWatcherTest extends PathWatcherTest {
  val tests = testsImpl
  override def expectedDeletions(dir: Path, subdir: Path): Set[Path] =
    if (Platform.isMac) Set(dir) else Set(dir, subdir)

  def defaultWatcher(callback: PathWatchers.Event => _): PathWatcher[PathWatchers.Event] = {
    val res = PathWatchers.get()
    res.addObserver(callback)
    res
  }
}

object NioPathWatcherTest extends PathWatcherTest {
  override def expectedDeletions(dir: Path, subdir: Path): Set[Path] = Set(dir, subdir)
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests {
        'ignore - {
          println("Not running NioDirectoryWatcherTest on platform other than osx on the jvm")
        }
      }

  def defaultWatcher(callback: PathWatchers.Event => _): PathWatcher[PathWatchers.Event] = {
    val res = new NioPathWatcher(new DirectoryRegistryImpl(),
                                 RegisterableWatchServices.get(),
                                 Executor.make("DirectoryWatcherTestExecutor-internal"))
    res.addObserver(callback)
    res
  }
}
