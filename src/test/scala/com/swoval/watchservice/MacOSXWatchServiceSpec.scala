package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.nio.file._
import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.scalatest.{Matchers, WordSpec}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import MacOSXWatchServiceSpec._

import scala.collection.mutable

class MacOSXWatchServiceSpec extends WordSpec with Matchers {

  "MacOSXWatchService" should {
    "poll" should {

      "return create events" in {
        withService { (service, _, _, _) =>
          withTempDirectory { dir =>
            service.watch(dir)
            val f = new File(s"${dir.toFile.getAbsolutePath}/foo")
            f.createNewFile()
            service.poll(defaultTimeout).pollEvents().asScala match {
              case Seq(event) => f.toPath shouldBe event.context
            }
          }
        }
      }
      "create one event per path between polls" in {
        withService { (service, _, onReg, onEvent) =>
          withTempDirectory { dir =>
            service.watch(dir)
            onReg.waitFor(dir, defaultTimeout)
            val f = new File(s"${dir.toFile.getAbsolutePath}/foo")
            f.createNewFile()
            onEvent.waitForCount(1, defaultTimeout)
            f.setLastModified(System.currentTimeMillis + 5000)
            onEvent.waitForCount(2, defaultTimeout)
            service.poll(defaultTimeout).pollEvents().asScala match {
              case Seq(createEvent, modifyEvent) =>
                createEvent.kind shouldBe ENTRY_CREATE
                createEvent.context shouldBe f.toPath
                modifyEvent.kind shouldBe ENTRY_MODIFY
                modifyEvent.context shouldBe f.toPath
            }
          }
        }
      }
      "return correct subdirectory" in {
        withService { (service, _, onReg, _) =>
          withTempDirectory { dir =>
            val subDir = new File(s"${dir.toFile.getAbsolutePath}/foo").toPath
            withDirectory(subDir) {
              service.watch(dir)
              service.watch(subDir)
              Seq(dir, subDir) foreach (onReg.waitFor(_, defaultTimeout))
              val f = new File(s"${subDir.toRealPath()}/bar")
              f.createNewFile()
              service.poll(defaultTimeout).watchable shouldBe subDir
            }
          }
        }
      }
    }
    "handle overflows" in {
      withService(queueSize = 2) { (service, onOffer, onReg, onEvent) =>
        withTempDirectory { dir =>
          val dirFile = new File(dir.toFile.getAbsolutePath)
          val subDir1 = new File(s"$dirFile/subdir1")
          val subDir2 = new File(s"$dirFile/subdir2")
          Seq(subDir1, subDir2) foreach { f =>
            f.mkdir()
            service.watch(f.toPath)
            onReg.waitFor(f.toPath, defaultTimeout)
          }
          val subDir1Paths = (0 to 5) map { i =>
            val f = new File(s"$subDir1/file$i")
            f.createNewFile()
            onEvent.waitForCount(i + 1, defaultTimeout)
            f.toPath
          }
          val subDir2File = new File(s"$subDir2/file")
          subDir2File.createNewFile()
          onOffer.waitForCount(2, defaultTimeout)
          val rawEvents = service.pollEvents()
          rawEvents exists { case (_, v) => v.exists(_.kind == OVERFLOW) } shouldBe true
          val events = rawEvents.map {
            case (k, v) => k.watchable -> v.filterNot(_.kind == OVERFLOW).map(_.context).toSet
          }
          events(subDir1.toPath) shouldBe (subDir1Paths take 2).toSet
          events(subDir2.toPath) shouldBe Set(subDir2File.toPath)
        }
      }
    }
    "Not inadvertently exclude files in subdirectory" in {
      /*
       * Because service.register asynchronously does a file scan and tries to save work by
       * not rescanning previously scanned directories, we need to be careful about how we
       * exclude paths in the scan. There was a bug where I was excluding directories that
       * hadn't yet been scanned because the directory was registered before the recursive
       * file scan was executed, and the recursive file scan was excluding the registered files.
       * This test case catches that bug.
       */
      withTempDirectory { dir =>
        val subDir = new File(s"${dir.toFile.getAbsolutePath}/foo")
        subDir.mkdir
        val file = new File(s"$subDir/baz.scala")
        file.createNewFile()
        withService { (service, _, onReg, _) =>
          service.watch(dir)
          service.watch(subDir.toPath)
          onReg.waitFor(dir, defaultTimeout)
          file.setLastModified(System.currentTimeMillis + 10000)
          service.poll(defaultTimeout).pollEvents().asScala match {
            case Seq(Event(k, _, f)) =>
              file.toPath shouldBe f
              // This would be ENTRY_CREATE if the bug described above were present.
              k shouldBe ENTRY_MODIFY
          }
        }
      }
    }
  }

}

object MacOSXWatchServiceSpec {
  val defaultLatency = 5.milliseconds

  val defaultQueueSize = 10

  val defaultTimeout = 5.seconds

  def withTempDirectory[R](f: Path => R): R = {
    val dir = Files.createTempDirectory(this.getClass.getSimpleName)
    withDirectory(dir)(f(dir.toRealPath()))
  }

  def withDirectory[R](path: Path)(f: => R): R = try {
    path.toFile.mkdir()
    f
  } finally {
    path.toFile.delete()
    ()
  }

  def withService[R](service: => MacOSXWatchService)(f: MacOSXWatchService => R): R = {
    val s = service
    try f(s) finally s.close()
  }

  def withService[R](
                      latency: Duration = defaultLatency,
                      queueSize: Int = defaultQueueSize,
                      onOffer: WatchKey => Unit = _ => {},
                      onRegister: WatchKey => Unit = _ => {},
                      onEvent: WatchEvent[_] => Unit = _ => {},
                    )(f: (MacOSXWatchService, OnOffer, OnReg, OnEvent) => R): R = {
    val offer = onOffer match {
      case o: OnOffer => o
      case f => new OnOffer(f)
    }
    val reg = onRegister match {
      case r: OnReg => r
      case f => new OnReg(f)
    }
    val event = onEvent match {
      case r: OnEvent => r
      case f => new OnEvent(f)
    }
    withService(
      new MacOSXWatchService(latency, queueSize)(offer, reg, event))(f(_, offer, reg, event))
  }

  def withService[R](f: (MacOSXWatchService, OnOffer, OnReg, OnEvent) => R): R = withService()(f)

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
    def watch(dir: Path) = s.register(dir, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
  }

  implicit class RichDuration(val d: Duration) extends AnyVal {
    private def toNanos = (d.toNanos - d.toMillis * 1e6).toInt

    def waitOn(lock: Object) = lock.synchronized(lock.wait(d.toMillis, toNanos))

    def waitOn(latch: CountDownLatch) = latch.await(d.toNanos, TimeUnit.NANOSECONDS)
  }

}
