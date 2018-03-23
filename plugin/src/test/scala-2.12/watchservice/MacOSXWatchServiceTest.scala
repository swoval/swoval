package com.swoval.watchservice

import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW }
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files => JFiles, Path => JPath, Paths => JPaths, _ }
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import com.swoval.files.Path
import com.swoval.files.test._
import com.swoval.test._
import utest._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

object MacOSXWatchServiceTest extends TestSuite {
  implicit def toJPath(p: Path): JPath = JPaths.get(p.fullName)
  val tests = testOn(MacOS) {
    'poll - {
      "return create events" - {
        withService { service =>
          withTempDirectorySync { dir =>
            service.watch(dir)
            val f = dir.resolve(Path("foo")).createNewFile()
            service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
              case Seq(event) => (f: JPath) ==> event.context
            }
          }
        }
      }
      "create one event per path between polls" - {
        withService { service =>
          withTempDirectorySync { dir =>
            service.watch(dir)
            val f = dir.resolve(Path("foo")).createNewFile()
            service.waitForEventCount(1, DEFAULT_TIMEOUT)
            f.setLastModified(System.currentTimeMillis + 5000)
            service.waitForEventCount(2, DEFAULT_TIMEOUT)
            service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
              case Seq(createEvent, modifyEvent) =>
                createEvent.kind ==> ENTRY_CREATE
                createEvent.context ==> (f: JPath)
                modifyEvent.kind ==> ENTRY_MODIFY
                modifyEvent.context ==> (f: JPath)
            }
          }
        }
      }
      "return correct subdirectory" - {
        withService { service =>
          withTempDirectorySync { dir =>
            val subDir = dir.resolve(Path("foo")).mkdirs()
            withDirectorySync(subDir) {
              service.watch(dir)
              service.watch(subDir)
              subDir.resolve(Path("bar")).createNewFile()
              service.filterPoll(DEFAULT_TIMEOUT)(_.watchable() === subDir).watchable ===> subDir
            }
          }
        }
      }
    }
    "handle overflows" - {
      withService(queueSize = 2) { service =>
        withTempDirectorySync { dir =>
          val subDir1 = dir.resolve(Path("subdir1"))
          val subDir2 = dir.resolve(Path(s"subdir2"))
          Seq(subDir1, subDir2) foreach { f =>
            f.mkdir()
            service.watch(f)
          }
          val subDir1Paths = (0 to 5) map { i =>
            val f = subDir1.resolve(Path(s"file$i")).createNewFile()
            service.waitForEventCount(i + 1, DEFAULT_TIMEOUT)
            f: JPath
          }
          val subDir2File: JPath = subDir2.resolve(Path("file")).createNewFile()
          service.waitForOfferCount(2, DEFAULT_TIMEOUT)
          val rawEvents = service.pollEvents()
          rawEvents exists { case (_, v) => v.exists(_.kind == OVERFLOW) }
          val events = rawEvents.map {
            case (k, v) => k.watchable -> v.filterNot(_.kind == OVERFLOW).map(_.context).toSet
          }
          events(subDir1) === (subDir1Paths take 2).toSet
          events(subDir2) === Set(subDir2File)
        }
      }
    }
    "Not inadvertently exclude files in subdirectory" - {
      /*
       * Because service.register asynchronously does a file scan and tries to save work by
       * not rescanning previously scanned directories, we need to be careful about how we
       * exclude paths in the scan. There was a bug where I was excluding directories that
       * hadn't yet been scanned because the directory was registered before the recursive
       * file scan was executed, and the recursive file scan was excluding the registered files.
       * This test case catches that bug.
       */
      withTempDirectory { dir =>
        val subDir = dir.resolve(Path("foo"))
        subDir.mkdirs()
        val file: Path = subDir.resolve(Path("baz.scala")).createNewFile()
        withService { service =>
          service.watch(dir)
          service.watch(subDir)
          file.setLastModified(System.currentTimeMillis() + 10000)
          service.filterPoll(DEFAULT_TIMEOUT)(_.watchable === subDir).pollEvents().asScala match {
            case Seq(Event(k, _, f: JPath)) =>
              (file: JPath) ===> f
              // This would be ENTRY_CREATE if the bug described above were present.
              k ==> ENTRY_MODIFY
          }
        }
      }
    }
  }

  def withService[R](
      latency: Duration = 1.millisecond,
      queueSize: Int = 10,
      onOffer: WatchKey => Unit = _ => {},
      onEvent: WatchEvent[_] => Unit = _ => {}
  )(f: MacOSXWatchService => R): Future[R] = {
    val offer = onOffer match {
      case o: OnOffer => o
      case f          => new OnOffer(f)
    }
    val event = onEvent match {
      case r: OnEvent => r
      case f          => new OnEvent(f)
    }
    using(new MacOSXWatchService(latency, queueSize)(offer, event))(f)
  }

  def withService[R](f: MacOSXWatchService => R): Future[R] = withService()(f)

  class OnReg(f: WatchKey => Unit) extends (WatchKey => Unit) {
    private[this] val latches = mutable.Map.empty[Watchable, CountDownLatch]
    private[this] val lock = new Object

    override def apply(key: WatchKey): Unit = {
      lock.synchronized(latches.getOrElseUpdate(key.watchable, new CountDownLatch(1)).countDown())
      f(key)
    }

    def waitFor(path: JPath, duration: Duration) = {
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

  implicit class RichCompare[T](val t: T) extends AnyVal {
    def ===[R](r: R)(implicit ev: R => T): Boolean = t == ev(r)
    def ===>[R](r: R)(implicit ev: R => T): Unit = t ==> ev(r)
  }
  implicit class RichSwovalPath(val s: Path) extends AnyVal {
    def createNewFile(): Path = Path(JFiles.createFile(s).toRealPath().toString)
    def setLastModified(ms: Long) = JFiles.setLastModifiedTime(s, FileTime.fromMillis(ms))
  }
  implicit class RichDuration(val d: Duration) {
    def waitOn(l: CountDownLatch) = l.await(d.toNanos, TimeUnit.NANOSECONDS)
    def waitOn(o: Object) = o.synchronized(o.wait(d.toMillis))
  }
  implicit class RichService(val s: MacOSXWatchService) extends AnyVal {
    def watch(dir: Path): Unit =
      s.register(JPaths.get(dir.fullName), ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
    def waitForEventCount(i: Int, duration: Duration): Unit = {
      s.onEvent match { case e: OnEvent => e.waitForCount(i, duration) }
    }
    def waitForOfferCount(i: Int, duration: Duration): Unit = {
      s.onOffer match { case e: OnOffer => e.waitForCount(i, duration) }
    }
    def filterPoll(timeout: Duration)(f: WatchKey => Boolean): WatchKey = {
      val limit = System.nanoTime + timeout.toNanos
      @tailrec def impl(): WatchKey = {
        val duration = (limit - System.nanoTime).nanos
        s.poll(duration) match {
          case k if f(k)                    => k
          case _ if System.nanoTime < limit => impl()
          case _                            => null
        }
      }
      impl()
    }
  }
}
