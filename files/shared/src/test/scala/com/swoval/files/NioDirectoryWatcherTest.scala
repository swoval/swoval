package com.swoval.files

import java.nio.file.{ Files => JFiles, Path => JPath }

import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.test._
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Failure

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
    'overflow - withTempDirectory { dir =>
      val subdirsToAdd = 10
      val queueSize = subdirsToAdd / 5
      val overflowLatch = new CountDownLatch(1)
      val subdirLatch = new CountDownLatch(1)
      val fileLatch = new CountDownLatch(subdirsToAdd)
      val subdirs = (1 to subdirsToAdd).map(i => dir.resolve(s"subdir-$i-overflow"))
      val last = subdirs.last
      val files = mutable.Set.empty[JPath]
      val callback: Callback = (_: DirectoryWatcher.Event) match {
        case e if e.kind == DirectoryWatcher.Event.Overflow => overflowLatch.countDown()
        case e if e.path == last =>
          if (System.getProperty("java.vm.name", "") == "Scala.js") overflowLatch.countDown()
          subdirLatch.countDown()
        case e if JFiles.isRegularFile(e.path) && !files.contains(e.path) =>
          files += e.path
          fileLatch.countDown()
        case _ =>
      }
      val service = new BoundedWatchService(queueSize, WatchService.newWatchService())
      usingAsync(new NioDirectoryWatcher(callback, service)) { w =>
        w.register(dir)
        subdirs.foreach(JFiles.createDirectory(_))
        overflowLatch
          .waitFor(DEFAULT_TIMEOUT / 2)(())
          .flatMap { _ =>
            subdirLatch.waitFor(DEFAULT_TIMEOUT) {
              subdirs.foreach(subdir => JFiles.write(subdir.resolve("foo.scala"), "foo".getBytes))
              fileLatch.waitFor(DEFAULT_TIMEOUT / 2) {
                new String(JFiles.readAllBytes(last.resolve("foo.scala"))) ==> "foo"
              }
            }
          }
      }.andThen {
        case Failure(_) =>
          if (overflowLatch.getCount > 0)
            println(s"Overflow latch was not triggered ${overflowLatch.getCount}")
          if (subdirLatch.getCount > 0) println("Subdirectory latch was not triggered")
          if (fileLatch.getCount > 0) println("File latch was not triggered")
      }
    }
  } else
    Tests {
      'ignore - { println("Not running NioDirectoryWatcherTest on scala.js for Mac OS") }
    }
}
