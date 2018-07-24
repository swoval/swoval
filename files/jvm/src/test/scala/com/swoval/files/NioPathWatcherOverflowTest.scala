package com.swoval.files

import java.nio.file.{ Files, Path }

import com.swoval.files.PathWatchers.Event
import com.swoval.files.test._
import com.swoval.functional.Consumer
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.util.Failure

object NioPathWatcherOverflowTest extends TestSuite {
  val tests = Tests {
    val subdirsToAdd = 200
    val executor = Executor.make("NioPathWatcherOverflowTest-executor")
    'overflows - withTempDirectory { dir =>
      val subdirs = 1 to subdirsToAdd map { i =>
        dir.resolve(s"subdir-$i")
      }
      val subdirLatch = new CountDownLatch(subdirsToAdd)
      val fileLatch = new CountDownLatch(subdirsToAdd)
      val addedSubdirs = mutable.Set.empty[Path]
      val addedFiles = mutable.Set.empty[Path]
      val files = subdirs.map(_.resolve("file"))
      val callback: Consumer[Event] = (e: Event) => {
        e.getPath.getFileName.toString match {
          case name if name.startsWith("subdir") && addedSubdirs.add(e.getPath) =>
            subdirLatch.countDown()
          case name if name == "file" && addedFiles.add(e.getPath) =>
            fileLatch.countDown()
          case _ =>
        }
      }
      usingAsync(
        PlatformWatcher.make(
          (e: PathWatchers.Event, _: Executor#Thread) => callback.accept(e),
          new BoundedWatchService(4, WatchServices.get()),
          Executor.make("NioPathWatcherExecutor"),
          new DirectoryRegistryImpl()
        )) { c =>
        c.register(dir, Integer.MAX_VALUE)
        executor.run((_: Executor#Thread) => subdirs.foreach(Files.createDirectory(_)))
        subdirLatch
          .waitFor(DEFAULT_TIMEOUT) {
            subdirs.toSet === addedSubdirs.toSet
            executor.run((_: Executor#Thread) => files.foreach(Files.createFile(_)))
          }
          .flatMap { _ =>
            fileLatch.waitFor(DEFAULT_TIMEOUT) {
              files.toSet === addedFiles.toSet
            }
          }
      }
    }
  }
}
