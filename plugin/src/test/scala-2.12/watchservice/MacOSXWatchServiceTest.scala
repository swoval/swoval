package com.swoval.watchservice

import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW }
import java.nio.file._
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import com.swoval.files.Path
import com.swoval.files.test._
import com.swoval.test.{ Files => _, _ }
import utest._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

object MacOSXWatchServiceTest extends TestSuite {
  import scala.language.implicitConversions
  val tests: Tests = testOn(MacOS) {
    'poll - {
      "return create events" - {
        withService { service =>
          withTempDirectorySync { dir =>
            service.watch(dir)
            val f = Files.createFile(dir.resolve("foo"))
            service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
              case Seq(event) => (f: Path) ==> event.context
            }
          }
        }
      }
      "create one event per path between polls" - {
        withService { service =>
          withTempDirectorySync { dir =>
            service.watch(dir)
            val f = Files.createFile(dir.resolve("foo"))
            service.waitForEventCount(1, DEFAULT_TIMEOUT)
            f.toFile.setLastModified(System.currentTimeMillis + 5000)
            service.waitForEventCount(2, DEFAULT_TIMEOUT)
            service.poll(DEFAULT_TIMEOUT).pollEvents().asScala match {
              case Seq(createEvent, modifyEvent) =>
                createEvent.kind ==> ENTRY_CREATE
                createEvent.context ==> (f: Path)
                modifyEvent.kind ==> ENTRY_MODIFY
                modifyEvent.context ==> (f: Path)
            }
          }
        }
      }
      "return correct subdirectory" - {
        withService { service =>
          withTempDirectory { dir =>
            val subDir = Files.createDirectories(dir.resolve("foo"))
            withDirectorySync(subDir) {
              service.watch(dir)
              service.watch(subDir)
              Files.createFile(subDir.resolve("bar"))
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
            Files.createDirectory(f)
            service.watch(f)
          }
          val subDir1Paths = (0 to 5) map { i =>
            val f = Files.createFile(subDir1.resolve(s"file$i"))
            service.waitForEventCount(i + 1, DEFAULT_TIMEOUT)
            f: Path
          }
          val subDir2File: Path = Files.createFile(subDir2.resolve("file"))
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
        val subDir = dir.resolve("foo")
        Files.createDirectories(subDir)
        val file: Path = Files.createFile(subDir.resolve("baz.scala"))
        withServiceSync { service =>
          service.watch(dir)
          service.watch(subDir)
          file.toFile.setLastModified(System.currentTimeMillis() + 10000)
          service
            .filterPoll(DEFAULT_TIMEOUT)(_.watchable === subDir)
            .pollEvents()
            .asScala match {
            case Seq(Event(k, _, f: Path)) =>
              (file: Path) ===> f
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
  )(f: MacOSXWatchService => Future[Unit]): Future[Unit] = {
    val offer = onOffer match {
      case o: OnOffer => o
      case o          => new OnOffer(o)
    }
    val event = onEvent match {
      case o: OnEvent => o
      case o          => new OnEvent(o)
    }
    usingAsync(new MacOSXWatchService(latency, queueSize)(offer, event))(f)
  }

  def withService[R](f: MacOSXWatchService => Future[Unit]): Future[Unit] = withService()(f)
  def withServiceSync[R](f: MacOSXWatchService => R): Future[Unit] = withService() { s =>
    val p = Promise[Unit]
    p.tryComplete(util.Try { f(s); () })
    p.future
  }

  class OnReg(f: WatchKey => Unit) extends (WatchKey => Unit) {
    private[this] val latches = mutable.Map.empty[Watchable, CountDownLatch]
    private[this] val lock = new Object

    override def apply(key: WatchKey): Unit = {
      lock.synchronized(latches.getOrElseUpdate(key.watchable, new CountDownLatch(1)).countDown())
      f(key)
    }

    def waitFor(path: Path, duration: Duration): Boolean = {
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

    def count: Int = lock.synchronized(_count)

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

    def reset(): Unit = lock.synchronized {
      _count = 0
    }
  }

  class OnOffer(f: WatchKey => Unit = _ => {}) extends EventCounter(f)

  class OnEvent(f: WatchEvent[_] => Unit = _ => {}) extends EventCounter(f)

  implicit class RichCompare[T](val t: T) extends AnyVal {
    def ===[R](r: R)(implicit ev: R => T): Boolean = t == ev(r)
    def ===>[R](r: R)(implicit ev: R => T): Unit = t ==> ev(r)
  }
  implicit class RichDuration(val d: Duration) {
    def waitOn(l: CountDownLatch): Boolean = l.await(d.toNanos, TimeUnit.NANOSECONDS)
    def waitOn(o: Object): Unit = o.synchronized(o.wait(d.toMillis))
  }
  implicit class RichService(val s: MacOSXWatchService) extends AnyVal {
    def watch(dir: Path): Unit =
      s.register(dir, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
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
