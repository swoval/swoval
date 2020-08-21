package com
package swoval
package files

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import com.swoval.files.FileCacheTest.FileCacheOps
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.TestHelpers._
import com.swoval.files.test._
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

trait FileCacheOverflowTest extends TestSuite with FileCacheTest {
  def getBounded[T <: AnyRef](
      converter: FileTreeDataViews.Converter[T],
      cacheObserver: FileTreeDataViews.CacheObserver[T]
  )(implicit testLogger: TestLogger): FileTreeRepository[T] =
    FileCacheTest.get[T](
      false,
      converter,
      cacheObserver,
      (r: DirectoryRegistry, _) => {
        PathWatchers.get(
          false,
          new BoundedWatchService(boundedQueueSize, RegisterableWatchServices.get()),
          r,
          testLogger
        )
      }
    )
  private val name = getClass.getSimpleName

  private val boundedQueueSize = System.getProperty("swoval.test.queue.size") match {
    case null => 4
    case c    => Try(c.toInt).getOrElse(4)
  }
  private val subdirsToAdd = System.getProperty("swoval.test.subdir.count") match {
    case null =>
      if (!Platform.isJVM) {
        if (Platform.isWin) 5 else 50
      } else 200
    case c => Try(c.toInt).getOrElse(200)
  }
  private val filesPerSubdir = System.getProperty("swoval.test.files.count") match {
    case null => 5
    case c    => Try(c.toInt).getOrElse(5)
  }
  private val timeout = DEFAULT_TIMEOUT * (if (Platform.isWin) 5 else 1)

  val testsImpl = Tests {
    'overflow - withTempDirectory { root =>
      implicit val logger: TestLogger = new CachingLogger
      val dir = root.resolve("overflow").resolve(name).createDirectories()
      // Windows is slow (at least on my vm)
      val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
      val creationLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
      val deletionLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
      val updateLatch = new CountDownLatch(subdirsToAdd)
      val subdirs = (1 to subdirsToAdd).map { i =>
        dir.resolve(s"subdir-$i")
      }
      val files = subdirs.flatMap { subdir =>
        (1 to filesPerSubdir).map { j =>
          subdir.resolve(s"file-$j")
        }
      }
      val pendingCreations = new ConcurrentHashMap[Path, CountDownLatch].asScala
      val pendingDeletions = new ConcurrentHashMap[Path, CountDownLatch].asScala
      val pendingUpdates = new ConcurrentHashMap[Path, CountDownLatch].asScala
      val allFiles = (subdirs ++ files).toSet
      allFiles.foreach { f =>
        pendingCreations.put(f, creationLatch)
        pendingDeletions.put(f, deletionLatch)
      }
      subdirs.foreach { f =>
        pendingUpdates.put(f.resolve("file-1"), updateLatch)
        pendingUpdates.put(f, updateLatch)
      }
      val foundFiles = mutable.Set.empty[Path]
      val updatedFiles = mutable.Set.empty[Path]
      val deletedFiles = mutable.Set.empty[Path]
      val observer = getObserver[Path](
        (e: Entry[Path]) =>
          pendingCreations.remove(e.path).foreach { l =>
            l.countDown()
            foundFiles.add(e.path)
          },
        (_: Entry[Path], e: Entry[Path]) => {
          if (Try(e.path.lastModified) == Success(3000)) {
            pendingUpdates.remove(e.path).foreach { l =>
              l.countDown()
              e.path.setLastModifiedTime(4000)
              updatedFiles.add(e.path)
            }
          }
        },
        (e: Entry[Path]) =>
          pendingDeletions.remove(e.path).foreach { l =>
            l.countDown()
            deletedFiles.add(e.path)
          },
        (_: IOException) => {}
      )
      usingAsync(getBounded[Path](identity, observer)) { c =>
        c.reg(dir)
        val lambdas =
          new java.util.ArrayList(
            (subdirs.map(d => () => d.createDirectories()) ++ files.map(f =>
              () => f.createFile(true)
            )).asJava
          )
        java.util.Collections.shuffle(lambdas)
        lambdas.asScala.foreach(executor.run(_))
        creationLatch
          .waitFor(timeout) {
            val found = c.ls(dir).map(_.path).toSet
            // Need to synchronize since files is first set on a different thread
            allFiles.synchronized {
              found === allFiles
            }
          }
          .flatMap { _ =>
            val lambdas = new java.util.ArrayList(subdirs.flatMap { d =>
              Seq(
                () => d setLastModifiedTime 3000L,
                () => d.resolve("file-1") setLastModifiedTime 3000L
              )
            }.asJava)
            java.util.Collections.shuffle(lambdas)
            lambdas.asScala.foreach(executor.run(_))
            updateLatch
              .waitFor(timeout) {
                val found = c.ls(dir).map(_.path).toSet
                allFiles.synchronized {
                  found === allFiles
                }
              }
              .flatMap { _ =>
                val lambdas =
                  new java.util.ArrayList(
                    (files.map(f => () => f.delete()) ++ subdirs
                      .map(f => () => f.deleteRecursive())).asJava
                  )
                java.util.Collections.shuffle(lambdas)
                lambdas.asScala.foreach(executor.run(_))
                deletionLatch
                  .waitFor(timeout) {
                    c.ls(dir) === Seq.empty
                  }
              }
          }
          .andThen {
            case Failure(e) =>
              println(s"Task failed $e")
              if (creationLatch.getCount > 0) {
                val count = creationLatch.getCount
                println((allFiles diff foundFiles).toSeq.take(10).sorted mkString "\n")
                10.milliseconds.sleep
                val newCount = creationLatch.getCount
                if (newCount == count)
                  println(s"$this Creation latch not triggered ($count)")
                else
                  println(
                    s"$this Creation latch not triggered, but still being decremented $newCount"
                  )
              }
              if (creationLatch.getCount <= 0 && updateLatch.getCount > 0) {
                val count = updateLatch.getCount
                10.milliseconds.sleep
                val newCount = updateLatch.getCount
                val expected = files.filter(_.getFileName.toString == "file-1").toSet
                println((expected diff updatedFiles.toSet).take(10).toSeq.sorted mkString "\n")
                if (newCount == count)
                  println(s"$this Update latch not triggered ($count)")
                else
                  println(
                    s"$this Update latch not triggered, but still being decremented $newCount"
                  )
              }
              if (
                creationLatch.getCount <= 0 && updateLatch.getCount <= 0 && deletionLatch.getCount > 0
              ) {
                val count = deletionLatch.getCount
                10.milliseconds.sleep
                val newCount = deletionLatch.getCount
                if (newCount == count)
                  println(s"$this Deletion latch not triggered ($count)")
                else
                  println(
                    s"$this Deletion latch not triggered, but still being decremented $newCount"
                  )
                println((allFiles diff deletedFiles.toSet).toSeq.sorted.take(10) mkString "\n")
              }
              executor.close()
            case _ =>
              executor.close()
          }
      }
    }
  }
}
object FileCacheOverflowTest extends FileCacheOverflowTest with DefaultFileCacheTest {
  private implicit class SyncOps[T <: AnyRef](val t: T) extends AnyVal {
    def sync[R](f: T => R): R = t.synchronized(f(t))
  }
  override def getBounded[T <: AnyRef](
      converter: FileTreeDataViews.Converter[T],
      cacheObserver: FileTreeDataViews.CacheObserver[T]
  )(implicit logger: TestLogger): FileTreeRepository[T] =
    if (Platform.isMac) FileCacheTest.getCached(false, converter, cacheObserver)
    else super.getBounded(converter, cacheObserver)
  val tests = testsImpl
}
object NioFileCacheOverflowTest extends FileCacheOverflowTest with NioFileCacheTest {
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests('ignore - {
        if (swoval.test.verbose)
          println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
