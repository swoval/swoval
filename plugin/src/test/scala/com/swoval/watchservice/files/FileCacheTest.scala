package com.swoval.watchservice.files

import java.io.File
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.{ Files, Path }
import java.util.concurrent.{ CountDownLatch, Executors }

import com.swoval.test._
import com.swoval.watchservice.files.Directory.Callback
import sbt.io.AllPassFilter
import utest._

import scala.collection.mutable
import scala.concurrent.duration._

object FileCacheTest extends TestSuite {
  val tests = Tests {
    'directories - {
      val (fileOptions, dirOptions) = (NoMonitor, DirectoryOptions.default)
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(FileCache(fileOptions, dirOptions)(_ => {})) { c =>
            c.register(parent)
            c.list(parent, recursive = true, AllPassFilter) === Seq(f.toFile)
          }
        }
        'monitor - withTempDirectory { dir =>
          val latch = new CountDownLatch(1)
          using(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
            c.register(dir)
            withTempFile(dir) { f =>
              (2 * DEFAULT_TIMEOUT).waitOn(latch)
              c.list(dir, recursive = true, AllPassFilter) === Seq(f.toFile)
            }
          }
        }
      }
    }
    'files - {
      val (fileOptions, dirOptions) = (FileOptions.default, NoMonitor)
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(FileCache(fileOptions, dirOptions)(_ => {})) { c =>
            c.register(parent)
            c.list(parent, recursive = true, AllPassFilter) === Seq(f.toFile)
          }

        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              using(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
                c.register(dir)
                withTempFile(dir) { f =>
                  DEFAULT_TIMEOUT.waitOn(latch)
                  c.list(dir, recursive = true, AllPassFilter) === Seq(f.toFile)
                }
              }
            }
            'directories - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              using(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
                c.register(dir)
                withTempDirectory(dir) { subdir =>
                  DEFAULT_TIMEOUT.waitOn(latch)
                  c.list(dir, recursive = true, AllPassFilter) === Seq(subdir.toFile)
                }
              }
            }
          }
        }
        'move - withTempDirectory { dir =>
          val latch = new CountDownLatch(2)
          val initial = Files.createTempFile(dir, "move", "")
          using(FileCache(fileOptions, dirOptions)(_ => latch.countDown())) { c =>
            c.list(dir, recursive = false, AllPassFilter) === Seq(initial.toFile)
            val moved = new File(s"$initial.moved")
            initial.toFile.renameTo(moved)
            DEFAULT_TIMEOUT.waitOn(latch)
            c.list(dir, recursive = false, AllPassFilter) === Seq(moved)
          }
        }
        'addmany - withTempDirectory { dir =>
          val filesToAdd = 1000
          val latch = new CountDownLatch(filesToAdd * 2)
          val added = mutable.Set.empty[Path]
          val callback: Callback = e =>
            added.synchronized {
              if (e.kind == ENTRY_CREATE && added.add(e.path)) {
                latch.countDown()
              }
          }
          using(FileCache(fileOptions, dirOptions)(callback)) { c =>
            c.register(dir)
            val executor = Executors.newSingleThreadExecutor()
            val files = mutable.Set.empty[Path]
            executor.submit((() =>
                               (0 until filesToAdd) foreach { i =>
                                 val subdir = Files.createTempDirectory(dir, s"subdir-$i-")
                                 val file = Files.createTempFile(subdir, s"file-$i-", "")
                                 files ++= Seq(subdir, file)
                               }): Runnable)
            try {
              (DEFAULT_TIMEOUT * 10).waitOn(latch)
              val found = c.list(dir, recursive = true, AllPassFilter).map(_.toPath).toSet
              (files.toSet diff found) === Set.empty[Path]
              (found diff files.toSet) === Set.empty[Path]
            } finally {
              executor.shutdownNow()
            }
          }
        }
      }
    }
  }
  implicit class RichCache(val fc: FileCacheImpl) extends AnyVal {
    def register(path: Path) = {
      val latch = new CountDownLatch(1)
      fc.register(path, _ => latch.countDown())
      DEFAULT_TIMEOUT.waitOn(latch)
      assert(latch.getCount == 0)
    }
  }
}
