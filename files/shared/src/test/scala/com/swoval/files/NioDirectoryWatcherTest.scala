package com.swoval.files

import java.nio.file.{ Paths, Files, Path }
import java.util.concurrent.TimeoutException

import com.swoval.functional.Consumer
import com.swoval.files.test._
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

object NioDirectoryWatcherTest extends TestSuite {
  // I am pretty sure there is a bug in libuv apple file system event implementation that
  // can cause events to be dropped. This is why I exclude the test from running on osx on scala.js.
  val tests = if (!((System.getProperty("java.vm.name") == "Scala.js") && Platform.isMac)) Tests {
    val events = new ArrayBlockingQueue[DirectoryWatcher.Event](1)
    implicit val latch: com.swoval.files.test.CountDownLatch = new CountDownLatch(1)

    def check(file: Path)(f: Path => Unit): Future[Unit] = events.poll(DEFAULT_TIMEOUT) { e =>
      e.path ==> file
      f(e.path)
    }

    'onCreate - withTempDirectory { dir =>
      val f = dir.resolve(Paths.get("create"))
      val callback: Consumer[DirectoryWatcher.Event] =
        (e: DirectoryWatcher.Event) => if (e.path == f) events.add(e)
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        w.register(dir)
        f.createFile()
        check(f)(_.exists)
      }
    }
    'onModify - withTempDirectory { dir =>
      val f = dir.resolve(Paths.get("modify"))
      val callback: Consumer[DirectoryWatcher.Event] =
        (e: DirectoryWatcher.Event) => if (e.path == f) events.add(e)
      usingAsync(new NioDirectoryWatcher(callback)) { w =>
        f.createFile()
        w.register(dir)
        f.setLastModifiedTime(0L)
        check(f)(_.lastModified ==> 0)
      }
    }
    'onDelete - withTempDirectory { dir =>
      val f = dir.resolve(Paths.get("delete"))
      val callback: Consumer[DirectoryWatcher.Event] =
        (e: DirectoryWatcher.Event) => if (e.path == f) events.add(e)
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
          val callback: Consumer[DirectoryWatcher.Event] =
            (e: DirectoryWatcher.Event) => if (e.path != dir && !e.path.isDirectory) events.add(e)
          usingAsync(new NioDirectoryWatcher(callback)) { w =>
            w.register(dir)
            val f = subdir.resolve(Paths.get("foo")).createFile()
            check(f)(p => assert(p.exists))
          }
        }
      }
    }
    'overflow - withTempDirectory { dir =>
      val subdirsToAdd = 10
      val queueSize = subdirsToAdd / 5
      val overflowLatch = new CountDownLatch(1)
      val subdirLatch = new CountDownLatch(1)
      val fileLatch = new CountDownLatch(subdirsToAdd)
      val subdirs = (1 to subdirsToAdd).map(i => dir.resolve(s"subdir-$i-overflow"))
      val last = subdirs.last
      val files = mutable.Set.empty[Path]
      val callback: Consumer[DirectoryWatcher.Event] = (_: DirectoryWatcher.Event) match {
        case e if e.kind == DirectoryWatcher.Event.Overflow => overflowLatch.countDown()
        case e if e.path == last =>
          overflowLatch.countDown()
          subdirLatch.countDown()
        case e if Files.isRegularFile(e.path) && !files.contains(e.path) =>
          files += e.path
          fileLatch.countDown()
        case _ =>
      }
      val service = new BoundedWatchService(1, WatchService.newWatchService())
      usingAsync(new NioDirectoryWatcher(callback, service)) { w =>
        w.register(dir)
        subdirs.foreach(Files.createDirectory(_))
        overflowLatch
          .waitFor(DEFAULT_TIMEOUT * 2)(())
          .flatMap { _ =>
            subdirLatch.waitFor(DEFAULT_TIMEOUT) {
              subdirs.foreach(subdir => Files.write(subdir.resolve("foo.scala"), "foo".getBytes))
              fileLatch.waitFor(DEFAULT_TIMEOUT) {
                new String(Files.readAllBytes(last.resolve("foo.scala"))) ==> "foo"
              }
            }
          }
      }.andThen {
        case Failure(_) =>
          if (overflowLatch.getCount > 0)
            println(
              s"Overflow latch was not triggered\noverflowLatch count: ${overflowLatch.getCount}" +
                s"\nfileLatch count: ${fileLatch.getCount}")
          if (subdirLatch.getCount > 0)
            println(s"Subdirectory latch was not triggered ${subdirLatch.getCount}")
          if (fileLatch.getCount > 0) println(s"File latch was not triggered ${fileLatch.getCount}")
      }
    }
    'unregister - withTempDirectory { base =>
      val dir = Files.createDirectories(base.resolve("dir"))
      val firstLatch = new CountDownLatch(1)
      val secondLatch = new CountDownLatch(1)
      val callback: Consumer[DirectoryWatcher.Event] = (e: DirectoryWatcher.Event) => {
        if (e.path.endsWith("file")) {
          firstLatch.countDown()
        } else if (e.path.endsWith("other-file")) {
          secondLatch.countDown()
        }
      }
      usingAsync(new NioDirectoryWatcher(callback)) { c =>
        c.register(dir)
        val file = Files.createFile(dir.resolve("file"))
        firstLatch
          .waitFor(DEFAULT_TIMEOUT) {
            assert(Files.exists(file))
          }
          .flatMap { _ =>
            c.unregister(dir)
            Files.createFile(dir.resolve("other-file"))
            secondLatch
              .waitFor(20.millis) {
                throw new IllegalStateException(
                  "Watcher triggered for path no longer under monitoring")
              }
              .recover {
                case _: TimeoutException => ()
              }
          }
      }
    }
    'depth - {
      'holes - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempDirectory(subdir) { secondSubdir =>
            withTempDirectory(secondSubdir) { thirdSubdir =>
              val subdirEvents = new ArrayBlockingQueue[DirectoryWatcher.Event](1)
              val callback: Consumer[DirectoryWatcher.Event] = (e: DirectoryWatcher.Event) => {
                if (e.path.endsWith("foo")) events.add(e)
                if (e.path.endsWith("bar")) subdirEvents.add(e)
              }
              usingAsync(new NioDirectoryWatcher(callback)) { w =>
                w.register(dir, 0)
                w.register(secondSubdir, 0)
                val file = thirdSubdir.resolve("foo").createFile()
                events
                  .poll(100.milliseconds) { _ =>
                    throw new IllegalStateException(
                      s"Event triggered for file $file that shouldn't be monitored")
                  }
                  .recoverWith {
                    case _: TimeoutException =>
                      w.register(dir, 4)
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
              val subdirEvents = new ArrayBlockingQueue[DirectoryWatcher.Event](1)
              val callback: Consumer[DirectoryWatcher.Event] = (e: DirectoryWatcher.Event) => {
                if (e.path.endsWith("baz")) events.add(e)
                if (e.path.endsWith("buzz")) subdirEvents.add(e)
              }
              usingAsync(new NioDirectoryWatcher(callback)) { w =>
                w.register(dir, 0)
                w.register(secondSubdir, 1)
                val file = subdir.resolve("baz").createFile()
                events
                  .poll(100.milliseconds) { _ =>
                    throw new IllegalStateException(
                      s"Event triggered for file $file that shouldn't be monitored")
                  }
                  .recoverWith {
                    case _: TimeoutException =>
                      w.register(dir, 2)
                      file.setLastModifiedTime(3000)
                      events
                        .poll(DEFAULT_TIMEOUT) { e =>
                          e.path ==> file
                          e.path.lastModified ==> 3000
                        }
                        .flatMap { _ =>
                          val subdirFile = subdir.resolve("buzz").createFile()
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
    'executorOverflow - withTempDirectory { dir =>
      val latch = new CountDownLatch(1)
      val secondLatch = new CountDownLatch(1)
      val executor = new TestExecutor("backedup-test-executor-thread")
      val callback: Consumer[DirectoryWatcher.Event] = (e: DirectoryWatcher.Event) => {
        if (latch.getCount > 0) {
          executor.overflow()
          latch.countDown()
        } else {
          secondLatch.countDown()
        }
      }
      usingAsync(new NioDirectoryWatcher(callback, WatchService.newWatchService(), executor, null)) {
        w =>
          w.register(dir, 0)
          val otherFile = dir.resolve("other-file")
          val file = dir.resolve("file").createFile()
          latch
            .waitFor(DEFAULT_TIMEOUT) {
              otherFile.createFile()
              100.milliseconds.sleep
              executor.clear()
            }
            .flatMap { _ =>
              secondLatch.waitFor(DEFAULT_TIMEOUT * 2) {}
            }
            .andThen {
              case Failure(_: TimeoutException) =>
                println(
                  s"Test timed out -- latch ${latch.getCount}, secondLatch ${secondLatch.getCount}")
            }
      }
    }
  } else
    Tests {
      'ignore - {
        println("Not running NioDirectoryWatcherTest on scala.js for Mac OS")
      }
    }
}
