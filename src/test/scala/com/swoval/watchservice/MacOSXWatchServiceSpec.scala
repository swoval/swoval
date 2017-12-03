package com.swoval.watchservice

import java.io.File
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.nio.file.{Files, Path, WatchKey}

import org.scalatest.{Matchers, WordSpec}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._

class MacOSXWatchServiceSpec extends WordSpec with Matchers {

  class OnOffer extends (WatchKey => Unit) {
    var count = 0
    private[this] val lock = new Object
    override def apply(key: WatchKey): Unit = lock.synchronized {
      count += 1
      lock.notifyAll()
    }
    final def waitForCount(i: Int, duration: Duration): Unit = {
      val ceiling = System.nanoTime + duration.toNanos
      @tailrec def waitUntil(i: Int): Unit = {
        val d = (ceiling - System.nanoTime).nanoseconds
        lock.synchronized {
          if (count != i) {
            d.waitOn(lock)
          }
        }
        if (lock.synchronized(count != i) && System.nanoTime < ceiling) waitUntil(i)
      }
      waitUntil(i)
    }
    def reset() = lock.synchronized { count = 0 }
  }

  implicit class RichService(val s: MacOSXWatchService) {
    def watch(dir: Path) = s.register(dir, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
  }

  implicit class RichDuration(val d: Duration) {
    private lazy val toNanos = (d.toNanos - d.toMillis * 1e6).toInt
    def waitOn(lock: Object) = lock.synchronized(lock.wait(d.toMillis, toNanos))
  }

  val defaultLatency = 5.milliseconds

  val defaultQueueSize = 10

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
  def withService[R](f: (MacOSXWatchService, OnOffer) => R): R = {
    val onOffer = new OnOffer
    withService(new MacOSXWatchService(defaultLatency, defaultQueueSize)(onOffer))(f(_, onOffer))
  }

  "MacOSXWatchService" should {
    "poll" should {

      "return create events" in {
        withService { (service, _) =>
          withTempDirectory { dir =>
            service.watch(dir)
            val f = new File(s"${dir.toFile.getAbsolutePath}/foo")
            f.createNewFile()
            service.poll(1.second).pollEvents().asScala match {
              case Seq(event) => f.toPath shouldBe event.context
            }
          }
        }
      }
      "create one event per path between polls" in {
        withService { (service, onOffer) =>
          withTempDirectory { dir =>
            service.watch(dir)
            val f = new File(s"${dir.toFile.getAbsolutePath}/foo")
            f.createNewFile()
            onOffer.waitForCount(1, 5.seconds)
            f.setLastModified(System.currentTimeMillis + 5000)
            onOffer.waitForCount(2, 5.seconds)
            service.poll(1.second).pollEvents().asScala match {
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
        withService { (service, onOffer) =>
          withTempDirectory { dir =>
            val subDir = new File(s"${dir.toFile.getAbsolutePath}/foo").toPath
            withDirectory(subDir) {
              service.watch(dir)
              service.watch(subDir)
              val f = new File(s"${subDir.toRealPath()}/bar")
              f.createNewFile()
              service.poll(1.second).watchable shouldBe subDir
            }
          }
        }
      }
    }
    "handle overflows" in {
      val onOffer = new OnOffer
      withService(new MacOSXWatchService(defaultLatency, 2)(onOffer)) { service =>
        withTempDirectory { dir =>
          val dirFile = new File(dir.toFile.getAbsolutePath)
          val subDir1 = new File(s"$dirFile/subdir1")
          val subDir2 = new File(s"$dirFile/subdir2")
          Seq(subDir1, subDir2) foreach (_.mkdir())
          service.watch(subDir1.toPath)
          service.watch(subDir2.toPath)
          val subDir1Paths = (0 to 5) map { i =>
            val f = new File(s"$subDir1/file$i")
            f.createNewFile()
            if (i < 2) onOffer.waitForCount(i, 1.second)
            f.toPath
          }
          val subDir2File = new File(s"$subDir2/file")
          subDir2File.createNewFile()
          onOffer.waitForCount(3, 1.seconds)
          val rawEvents = service.pollEvents()
          rawEvents exists { case (_, v) => v.exists(_.kind == OVERFLOW) } shouldBe  true
          val events = rawEvents.map {
            case (k, v) => k.watchable -> v.filterNot(_.kind == OVERFLOW).map(_.context).toSet
          }
          events(subDir1.toPath) shouldBe (subDir1Paths take 2).toSet
          events(subDir2.toPath) shouldBe Set(subDir2File.toPath)
        }
      }
    }
  }
}
