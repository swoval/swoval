package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW }
import java.nio.file._
import java.util.concurrent.CountDownLatch

import com.swoval.test._
import utest._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import com.swoval.test._

object MacOSXWatchServiceSpec extends TestSuite {
  val tests = Tests {
    'MacOSXWatchService - {
      'poll - {
        "return create events" - {
          withService { service =>
            withTempDirectory { dir =>
              service.watch(dir)
              val f = new File(s"${dir.toFile.getAbsolutePath}/foo")
              f.createNewFile()
              service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
                case Seq(event) => f.toPath ==> event.context
              }
            }
          }
        }
        "create one event per path between polls" - {
          withService { service =>
            withTempDirectory { dir =>
              service.watch(dir)
              val f = new File(s"${dir.toFile.getAbsolutePath}/foo")
              f.createNewFile()
              service.waitForEventCount(1, DEFAULT_TIMEOUT)
              f.setLastModified(System.currentTimeMillis + 5000)
              service.waitForEventCount(2, DEFAULT_TIMEOUT)
              service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
                case Seq(createEvent, modifyEvent) =>
                  createEvent.kind ==> ENTRY_CREATE
                  createEvent.context ==> f.toPath
                  modifyEvent.kind ==> ENTRY_MODIFY
                  modifyEvent.context ==> f.toPath
              }
            }
          }
        }
        "return correct subdirectory" - {
          withService { service =>
            withTempDirectory { dir =>
              val subDir = new File(s"${dir.toFile.getAbsolutePath}/foo").toPath
              withDirectory(subDir) {
                service.watch(dir)
                service.watch(subDir)
                val f = new File(s"${subDir.toRealPath()}/bar")
                f.createNewFile()
                service.poll(DEFAULT_TIMEOUT).watchable ==> subDir
              }
            }
          }
        }
      }
      "handle overflows" - {
        withService(queueSize = 2) { service =>
          withTempDirectory { dir =>
            val dirFile = new File(dir.toFile.getAbsolutePath)
            val subDir1 = new File(s"$dirFile/subdir1")
            val subDir2 = new File(s"$dirFile/subdir2")
            Seq(subDir1, subDir2) foreach { f =>
              f.mkdir()
              service.watch(f.toPath)
            }
            val subDir1Paths = (0 to 5) map { i =>
              val f = new File(s"$subDir1/file$i")
              f.createNewFile()
              service.waitForEventCount(i + 1, DEFAULT_TIMEOUT)
              f.toPath
            }
            val subDir2File = new File(s"$subDir2/file")
            subDir2File.createNewFile()
            service.waitForOfferCount(2, DEFAULT_TIMEOUT)
            val rawEvents = service.pollEvents()
            rawEvents exists { case (_, v) => v.exists(_.kind == OVERFLOW) }
            val events = rawEvents.map {
              case (k, v) => k.watchable -> v.filterNot(_.kind == OVERFLOW).map(_.context).toSet
            }
            events(subDir1.toPath) ==> (subDir1Paths take 2).toSet
            events(subDir2.toPath) ==> Set(subDir2File.toPath)
          }
        }
      }
      "Not inadvertently exclude files in subdirectory" - {
        /*
         * Because service.register asynchronously does a file scan and tries to save work by
         * not rescanning previously scanned directories, we need to be careful about how we
         * exclude paths - the scan. There was a bug where I was excluding directories that
         * hadn't yet been scanned because the directory was registered before the recursive
         * file scan was executed, and the recursive file scan was excluding the registered files.
         * This test case catches that bug.
         */
        withTempDirectory { dir =>
          val subDir = new File(s"${dir.toFile.getAbsolutePath}/foo")
          subDir.mkdir
          val file = new File(s"$subDir/baz.scala")
          file.createNewFile()
          withService { service =>
            service.watch(dir)
            service.watch(subDir.toPath)
            file.setLastModified(System.currentTimeMillis + 10000)
            service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
              case Seq(Event(k, _, f)) =>
                file.toPath ==> f
                // This would be ENTRY_CREATE if the bug described above were present.
                k ==> ENTRY_MODIFY
            }
          }
        }
      }
    }

  }

  def withService[R](
      latency: Duration = 1.millisecond,
      queueSize: Int = 10,
      onOffer: WatchKey => Unit = _ => {},
      onRegister: WatchKey => Unit = _ => {},
      onEvent: WatchEvent[_] => Unit = _ => {},
  )(f: MacOSXWatchService => R): R = {
    val offer = onOffer match {
      case o: OnOffer => o
      case f          => new OnOffer(f)
    }
    val reg = onRegister match {
      case r: OnReg => r
      case f        => new OnReg(f)
    }
    val event = onEvent match {
      case r: OnEvent => r
      case f          => new OnEvent(f)
    }
    using(new MacOSXWatchService(latency, queueSize)(offer, reg, event))(f)
  }

  def withService[R](f: MacOSXWatchService => R): R = withService()(f)

  class OnReg(f: WatchKey => Unit) extends (WatchKey => Unit) {
    private[this] val latches = mutable.Map.empty[Watchable, CountDownLatch]
    private[this] val lock = new Object

    override def apply(key: WatchKey): Unit = {
      lock.synchronized(latches.getOrElseUpdate(key.watchable, new CountDownLatch(1)).countDown())
      f(key)
    }

    def waitFor(path: Path, duration: Duration) = {
      duration.waitOn(lock.synchronized(latches.getOrElseUpdate(path, new CountDownLatch(1))))
    }
  }

  class EventCounter[T](f: T => Unit) extends (T => Unit) {
    private var _count = 0
    private[this] val lock = new Object

    override def apply(t: T): Unit = {
      lock.synchronized {
        _count += 1
        lock.notifyAll()
      }
      f(t)
    }

    def count = lock.synchronized(_count)

    final def waitForCount(i: Int, duration: Duration): Unit = {
      val ceiling = System.nanoTime + duration.toNanos

      @tailrec def waitUntil(i: Int): Unit = {
        val d = (ceiling - System.nanoTime).nanoseconds
        lock.synchronized {
          if (_count != i) {
            d.waitOn(lock)
          }
        }
        if (lock.synchronized(_count != i) && System.nanoTime < ceiling) waitUntil(i)
      }

      waitUntil(i)
    }

    def reset() = lock.synchronized {
      _count = 0
    }
  }

  class OnOffer(f: WatchKey => Unit = _ => {}) extends EventCounter(f)

  class OnEvent(f: WatchEvent[_] => Unit = _ => {}) extends EventCounter(f)

  implicit class RichService(val s: MacOSXWatchService) extends AnyVal {
    def watch(dir: Path) = {
      s.register(dir, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
      s.onRegister match { case o: OnReg => o.waitFor(dir, DEFAULT_TIMEOUT) }
    }
    def waitForEventCount(i: Int, duration: Duration) = {
      s.onEvent match { case e: OnEvent => e.waitForCount(i, duration) }
    }
    def waitForOfferCount(i: Int, duration: Duration) = {
      s.onOffer match { case e: OnOffer => e.waitForCount(i, duration) }
    }
  }
}
