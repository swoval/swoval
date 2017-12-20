package com.swoval.watcher

import java.io.File
import java.nio.file.{ Files, Path }
import java.util.concurrent.{ ArrayBlockingQueue, CountDownLatch }

import com.swoval.test._
import com.swoval.watcher.DirectoryWatcher._
import utest._

import scala.concurrent.duration._

object AppleDirectoryWatcherSpec extends TestSuite {
  val DEFAULT_LATENCY = 5.milliseconds
  implicit class RichAppleDirectoryWatcher(val w: AppleDirectoryWatcher) extends AnyVal {
    def waitToRegister(path: Path)(implicit latch: CountDownLatch) = {
      w.register(path, _ => latch.countDown())
      DEFAULT_TIMEOUT.waitOn(latch)
    }
  }
  implicit class RichFile(val file: File) extends AnyVal {
    def ===(path: Path): Unit = file.toPath.toRealPath() ==> path.toRealPath()
    def ===(f: File): Unit = ===(f.toPath)
  }
  val tests = Tests {
    'AppleDirectoryWatcher - {
      implicit val latch: CountDownLatch = new CountDownLatch(1)
      "directories" - {
        val flags = new Flags.Create(Flags.Create.NoDefer)
        'onCreate - withTempDirectory { dir =>
          val events = new ArrayBlockingQueue[FileEvent](10)
          val callback: Callback = e => events.add(e)

          using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
            w.waitToRegister(dir)
            val fileName = s"$dir/foo"
            val f = new File(fileName)
            f.createNewFile()
            val event = events.poll(DEFAULT_TIMEOUT)
            new File(event.fileName) === dir
          }
        }
        'onModify - withTempDirectory { dir =>
          val events = new ArrayBlockingQueue[FileEvent](10)
          val callback: Callback = e => events.add(e)

          using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
            val fileName = s"$dir/foo"
            val f = new File(fileName)
            f.createNewFile()
            w.waitToRegister(dir)
            f.setLastModified(0L)
            val event = events.poll(DEFAULT_TIMEOUT)
            new File(event.fileName) === dir
          }
        }
        'onDelete - withTempDirectory { dir =>
          val events = new ArrayBlockingQueue[FileEvent](10)
          val callback: Callback = e => events.add(e)

          using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
            val fileName = s"$dir/foo"
            val f = new File(fileName)
            f.createNewFile()
            w.waitToRegister(dir)
            f.delete()
            val event = events.poll(DEFAULT_TIMEOUT)
            new File(event.fileName) === dir
          }
        }
        'subdirectories - {
          'onCreate - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              val events = new ArrayBlockingQueue[FileEvent](10)
              val callback: Callback = { e =>
                if (new File(e.fileName).toPath != dir) events.add(e)
              }

              using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
                w.waitToRegister(dir)
                val fileName = s"$subdir/foo"
                val f = new File(fileName)
                f.createNewFile()
                val event = events.poll(DEFAULT_TIMEOUT)
                new File(event.fileName) === subdir
              }
            }
          }
        }
      }
      'files - {
        val flags = new Flags.Create(Flags.Create.NoDefer).setFileEvents
        "handle file creation events" - {
          withTempDirectory { dir =>
            val events = new ArrayBlockingQueue[FileEvent](10)
            val callback: Callback = e => { if (e.itemIsFile) events.add(e) }

            using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
              w.waitToRegister(dir)
              val fileName = s"$dir/foo"
              val f = new File(fileName)
              f.createNewFile()
              val event = events.poll(DEFAULT_TIMEOUT)
              assert(event.isNewFile)
            }
          }
        }
        "handle file touch events" - withTempFile { f =>
          val events = new ArrayBlockingQueue[FileEvent](10)
          val callback: Callback = e => { if (e.itemIsFile) events.add(e) }
          val fileName = f.toString
          using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
            w.waitToRegister(f.getParent)
            f.toFile.setLastModified(0L)
            val event = events.poll(DEFAULT_TIMEOUT)
            event.fileName ==> fileName
            assert(event.isTouched)
          }
        }
        "handle file modify events" - withTempFile { f =>
          val events = new ArrayBlockingQueue[FileEvent](10)
          val callback: Callback = e => { if (e.itemIsFile) events.add(e) }
          val fileName = f.toString
          using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
            w.waitToRegister(f.getParent)
            Files.write(f, "hello".getBytes)
            val event = events.poll(DEFAULT_TIMEOUT)
            event.fileName ==> fileName
            assert(event.isModified)
          }
        }
        "handle file deletion events" - withTempFile { f =>
          val events = new ArrayBlockingQueue[FileEvent](10)
          val callback: Callback = e => { if (e.itemIsFile) events.add(e) }
          val fileName = f.toString
          using(new AppleDirectoryWatcher(DEFAULT_LATENCY, flags)(callback)) { w =>
            w.waitToRegister(f.getParent)
            f.toFile.delete()
            val event = events.poll(DEFAULT_TIMEOUT)
            event.fileName ==> fileName

            assert(event.isRemoved)
          }
        }
      }
    }
  }
}
