package com
package swoval
package files

import java.nio.file.{ Path, Paths }
import java.util.concurrent.{ TimeUnit, TimeoutException }

import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatchers.Event.Kind
import com.swoval.files.PathWatchers.Event.Kind.{ Delete, Modify }
import com.swoval.files.TestHelpers._
import com.swoval.files.apple.Flags
import com.swoval.files.test.{ ArrayBlockingQueue, _ }
import com.swoval.functional.Consumer
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

trait PathWatcherTest extends TestSuite {
  type Event = PathWatchers.Event
  val DEFAULT_LATENCY = 5.milliseconds
  val fileFlags = new Flags.Create().setNoDefer().setFileEvents()
  def checkModified(e: Event): Boolean = e.getKind == Modify
  def defaultWatcher(callback: PathWatchers.Event => _, followLinks: Boolean = false)(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event]
  def unregisterTest(followLinks: Boolean): Future[Unit] = withTempDirectory { root =>
    implicit val logger: TestLogger = new CachingLogger
    val base = root.resolve("unregister").createDirectory()
    val dir = base.resolve("nested").createDirectory()
    val firstLatch = new CountDownLatch(1)
    val secondLatch = new CountDownLatch(2)
    val callback = (e: PathWatchers.Event) => {
      if (e.path.endsWith("foo")) {
        firstLatch.countDown()
      } else if (e.path.endsWith("bar")) {
        secondLatch.countDown()
      }
    }
    import Implicits.executionContext
    usingAsync(defaultWatcher(callback, followLinks)) { c =>
      c.register(base)
      val file = dir.resolve("foo").createFile()
      firstLatch
        .waitFor(DEFAULT_TIMEOUT) {
          assert(file.exists())
        }
        .flatMap { _ =>
          c.unregister(base)
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

  val testsImpl = Tests {
    val events = new ArrayBlockingQueue[PathWatchers.Event](10)
    'files - {
      'onCreate - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val callback = (e: PathWatchers.Event) => {
          if (e.path.endsWith("foo")) events.add(e)
        }

        usingAsync(defaultWatcher(callback)) { w =>
          w.register(dir)
          val file = dir.resolve(Paths.get("foo")).createFile()
          events.poll(DEFAULT_TIMEOUT)(_.path ==> file)
        }
      }
      'onTouch - withTempFile { f =>
        implicit val logger: TestLogger = new CachingLogger
        val callback =
          (e: PathWatchers.Event) =>
            if (e.path == f && checkModified(e) && f.lastModified == 3000L)
              events.add(e)
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f.setLastModifiedTime(3000L)
          events.poll(DEFAULT_TIMEOUT)(_ ==> new Event(TypedPaths.get(f), Modify))
        }
      }
      'onModify - withTempFile { f =>
        implicit val logger: TestLogger = new CachingLogger
        val callback =
          (e: PathWatchers.Event) =>
            if (e.path == f && checkModified(e) && f.read == "hello")
              events.add(e)
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(f.getParent)
          f write "hello"
          f.setLastModifiedTime(3000L)
          events.poll(DEFAULT_TIMEOUT)(_.path ==> f)
        }
      }
      'onDelete - {
        'file - withTempFile { f =>
          implicit val logger: TestLogger = new CachingLogger
          val callback = (e: PathWatchers.Event) => {
            if (!e.path.exists && e.getKind == Delete && e.path == f)
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
        'directory - withTempDirectory { dir =>
          implicit val logger: TestLogger = new CachingLogger
          val callback = (e: PathWatchers.Event) => {
            if (!e.path.exists && e.getKind == Delete && e.path == dir)
              events.add(e)
          }
          usingAsync(defaultWatcher(callback)) { w =>
            w.register(dir)
            dir.delete()
            events.poll(DEFAULT_TIMEOUT) { e =>
              e ==> new Event(TypedPaths.get(dir), Delete)
            }
          }
        }
      }
      'redundant - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        if (Platform.isMac && this != PollingPathWatcherTest) {
          val events = new ArrayBlockingQueue[String](10)
          val callback: Consumer[String] = (stream: String) => events.add(stream)
          withTempDirectory(dir) { subdir =>
            val watcher = new ApplePathWatcher(
              DEFAULT_LATENCY.toNanos,
              TimeUnit.NANOSECONDS,
              fileFlags,
              callback,
              new DirectoryRegistryImpl,
              logger
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
      'unregister - {
        'follow - unregisterTest(true)
        'noFollow - unregisterTest(false)
      }
    }
    'register - {
      'file - withTempFile { file =>
        implicit val logger: TestLogger = new CachingLogger
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
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("change").resolve("debug").createDirectories()
        val file = dir.resolve("file").createFile()
        val dirLatch = new CountDownLatch(1)
        val fileLatch = new CountDownLatch(1)
        val subfile = file.resolve("subfile")
        val callback = (e: PathWatchers.Event) => {
          if (e.path == file) dirLatch.countDown()
          else if (e.path == subfile) fileLatch.countDown()
        }

        usingAsync(defaultWatcher(callback)) { w =>
          w.register(file)
          file.setLastModifiedTime(3000)
          dirLatch
            .waitFor(DEFAULT_TIMEOUT) {
              file.lastModified ==> 3000
              file.delete()
              file.createDirectory()
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
          implicit val logger: TestLogger = new CachingLogger
          val dir = root.resolve("initial").resolve("debug").createDirectories()
          val dirLatch = new CountDownLatch(1)
          val fileLatch = new CountDownLatch(1)
          val subdir = dir.resolve("subdir")
          val file = subdir.resolve("file-initial")
          val callback = (e: PathWatchers.Event) => {
            if (e.path == subdir && e.getTypedPath.exists) dirLatch.countDown()
            else if (e.path == file && e.getTypedPath.exists) fileLatch.countDown()
          }

          usingAsync(defaultWatcher(callback)) { w =>
            w.register(subdir)
            subdir.createDirectory()
            dirLatch
              .waitFor(DEFAULT_TIMEOUT) {
                assert(subdir.exists())
                file.createFile()
              }
              .flatMap { _ =>
                fileLatch.waitFor(DEFAULT_TIMEOUT) {
                  assert(file.exists())
                }
              }
          }.andThen {
            case Success(_) =>
            case Failure(_) => System.out.println(s"absent.initially failed for $file")
          }
        }
      }
      'relative - withTempDirectory(targetDir) { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val latch = new CountDownLatch(1)
        val file = dir.resolve("file")
        val callback =
          (e: PathWatchers.Event) => if (e.path.getFileName.toString == "file") latch.countDown()
        usingAsync(defaultWatcher(callback)) { w =>
          w.register(baseDir.relativize(dir))
          file.createFile()
          latch.waitFor(DEFAULT_TIMEOUT) {
            assert(file.exists())
          }
        }
      }
    }
    'directory - {
      'delete - (if (Platform.isJVM || Platform.isMac) {
                   implicit val logger: TestLogger = new CachingLogger
                   withTempDirectory { dir =>
                     withTempDirectory(dir) { subdir =>
                       val deletions = mutable.Set.empty[Path]
                       val subdirDeletionLatch = new CountDownLatch(1)
                       val dirDeletionLatch = new CountDownLatch(1)
                       val creationLatch = new CountDownLatch(1)
                       var creationPending = false
                       val callback = (e: PathWatchers.Event) => {
                         if (e.getKind == Kind.Delete && deletions.add(e.path)) {
                           if (e.path == dir) dirDeletionLatch.countDown()
                           else if (e.path == subdir)
                             subdirDeletionLatch.countDown()
                         }
                         if (e.path.equals(dir) && creationPending) {
                           creationPending = false
                           creationLatch.countDown()
                         }
                       }
                       usingAsync(defaultWatcher(callback)) { w =>
                         w.register(dir, Integer.MAX_VALUE)
                         subdir.deleteRecursive()
                         subdirDeletionLatch
                           .waitFor(DEFAULT_TIMEOUT) {
                             deletions.toSet === Set(subdir)
                             dir.deleteRecursive()
                           }
                           .flatMap { _ =>
                             dirDeletionLatch
                               .waitFor(DEFAULT_TIMEOUT) {
                                 deletions.toSet === Set(dir, subdir)
                                 creationPending = true
                                 dir.createDirectory()
                               }
                               .flatMap { _ =>
                                 creationLatch.waitFor(DEFAULT_TIMEOUT) {}
                               }
                           }
                       }.andThen {
                         case Failure(e) => println(s"Test failed, got deletions: $deletions")
                         case _          =>
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
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory(dir) { subdir =>
          val file = subdir.resolve("foo")
          val callback =
            (e: PathWatchers.Event) => if (e.path == file) events.add(e)
          usingAsync(defaultWatcher(callback)) { w =>
            w.register(dir, 0)
            file.createFile()
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
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { secondSubdir =>
              withTempDirectory(secondSubdir) { thirdSubdir =>
                val subdirEvents = new ArrayBlockingQueue[PathWatchers.Event](1)
                val callback = (e: PathWatchers.Event) => {
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
        'extend - withTempDirectory { root =>
          implicit val logger: TestLogger = new CachingLogger
          val dir = root.resolve("extend").resolve("debug").createDirectories()
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { secondSubdir =>
              withTempDirectory(secondSubdir) { thirdSubdir =>
                val subdirEvents = new ArrayBlockingQueue[PathWatchers.Event](1)
                val callback = (e: PathWatchers.Event) => {
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
                        file setLastModifiedTime 3000
                        val files = events.poll(DEFAULT_TIMEOUT) { e =>
                          e.path ==> file
                          e.path.lastModified ==> 3000
                        }
                        files.flatMap { _ =>
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
    'converter - {
      'simple - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val converter: Converter[java.lang.Long] =
          (tp: TypedPath) => java.lang.Long.valueOf(tp.getPath.lastModified)
        usingAsync(PathWatchers.followSymlinks(converter, logger)) { pw =>
          val latch = new CountDownLatch(1)
          pw.addObserver(new Observer[java.lang.Long] {
            override def onError(t: _root_.java.lang.Throwable): Unit = {}
            override def onNext(t: java.lang.Long): Unit = if (t == 3000L) latch.countDown()
          })
          pw.register(dir, Int.MaxValue)
          dir setLastModifiedTime 3000L
          latch.waitFor(DEFAULT_TIMEOUT) {
            dir.lastModified ==> 3000L
          }
        }
      }
    }
  }
}

object PathWatcherTest extends PathWatcherTest {
  val tests = testsImpl

  override def defaultWatcher(callback: PathWatchers.Event => _, followLinks: Boolean)(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event] = {
    val res =
      if (followLinks) PathWatchers.followSymlinks(testLogger)
      else PathWatchers.noFollowSymlinks(testLogger)
    res.addObserver(callback)
    res
  }
}

object NioPathWatcherTest extends PathWatcherTest {
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests {
        'ignore - {
          if (swoval.test.verbose)
            println("Not running NioDirectoryWatcherTest on platform other than osx on the jvm")
        }
      }

  override def defaultWatcher(callback: PathWatchers.Event => _, followLinks: Boolean)(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event] = {
    val res = PlatformWatcher.make(new DirectoryRegistryImpl(), testLogger)
    res.addObserver(callback)
    res
  }
}

object PollingPathWatcherTest extends PathWatcherTest {
  val tests = testsImpl
  override def checkModified(event: PathWatchers.Event): Boolean = event.getKind != Delete
  override def defaultWatcher(callback: PathWatchers.Event => _, followLinks: Boolean)(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event] = {
    val res = PathWatchers.polling(followLinks, 100, TimeUnit.MILLISECONDS)
    res.addObserver(callback)
    res
  }
}
