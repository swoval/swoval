package com.swoval.files

import java.io.IOException
import java.nio.file.attribute.FileTime
import java.nio.file.{ FileSystemLoopException, Files, Path => JPath }

import com.swoval.files.Directory._
import com.swoval.files.EntryOps._
import com.swoval.files.FileCacheTest.FileCacheOps
import com.swoval.files.test._
import com.swoval.test._
import utest._
import Implicits.executionContext
import com.swoval.files.SymlinkWatcher.OnLoop

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Failure

trait FileCacheTest extends TestSuite {
  def factory: DirectoryWatcher.Factory
  def identity: Converter[JPath] = (p: JPath) => p
  def simpleCache(f: Entry[JPath] => Unit): FileCache[JPath] =
    FileCache.apply(((p: JPath) => p): Converter[JPath],
                    factory,
                    Observers.apply(f: OnChange[JPath]))
  class LoopObserver(val latch: CountDownLatch) extends Observer[JPath] {
    override def onCreate(newEntry: Entry[JPath]): Unit = {}
    override def onDelete(oldEntry: Entry[JPath]): Unit = {}
    override def onUpdate(oldEntry: Entry[JPath], newEntry: Entry[JPath]): Unit = {}
    override def onError(path: JPath, exception: IOException): Unit = latch.countDown()
  }

  val testsImpl: Tests = Tests {
    'directory - {
      'subdirectories - {
        'callback - withTempDirectory { dir =>
          val events = new ArrayBlockingQueue[JPath](2)
          val eventSet = mutable.Set.empty[JPath]
          usingAsync(simpleCache((cacheEntry: Entry[JPath]) =>
            if (eventSet.add(cacheEntry.path)) events.add(cacheEntry.path))) { c =>
            c.register(dir)
            withTempDirectory(dir) { subdir =>
              withTempFile(subdir) { f =>
                events.poll(DEFAULT_TIMEOUT)(_ ==> subdir).flatMap { _ =>
                  events.poll(DEFAULT_TIMEOUT) { e =>
                    e ==> f
                    c.ls(dir).map(_.path).toSet === Set(subdir, f)
                    ()
                  }
                }
              }
            }
          }
        }
      }
    }
    'files - {
      'register - {
        'existing - withTempFile { f =>
          val parent = f.getParent
          using(simpleCache(_ => {})) { c =>
            c.reg(parent)
            c.ls(parent) === Seq(f)
          }
        }
        'monitor - {
          'new - {
            'files - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
                c.reg(dir)
                withTempFile(dir) { f =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Seq(f)
                  }
                }
              }
            }
            'directories - withTempDirectory { dir =>
              val latch = new CountDownLatch(1)
              usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
                c.reg(dir)
                withTempDirectory(dir) { subdir =>
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Seq(subdir)
                  }
                }
              }
            }
          }
        }
        'move - withTempDirectory { dir =>
          val latch = new CountDownLatch(2)
          val initial = Files.createTempFile(dir, "move", "")
          val moved = Path(s"${initial.toString}.moved")
          val onChange: OnChange[JPath] = (_: Entry[JPath]) => latch.countDown()
          val onUpdate: OnUpdate[JPath] = (_: Entry[JPath], _: Entry[JPath]) => {}
          val onError: OnError = (_: JPath, _: IOException) => {}
          val observer = Observers.apply(onChange, onUpdate, onChange, onError)
          usingAsync(FileCache.apply(identity, factory, observer)) { c =>
            c.reg(dir, recursive = false)
            c.ls(dir, recursive = false) === Seq(initial)
            initial.renameTo(moved)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir, recursive = false) === Seq(moved)
            }
          }
        }
        'addmany - withTempDirectory { dir =>
          // Windows is slow (at least on my vm)
          val subdirsToAdd = if (System.getProperty("java.vm.name", "") == "Scala.js") {
            if (Platform.isWin) 5 else 50
          } else 2000
          val timeout = DEFAULT_TIMEOUT * 60
          val filesPerSubdir = 4
          val executor = Executor.make("com.swoval.files.FileCacheTest.addmany.worker-thread")
          val creationLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
          val deletionLatch = new CountDownLatch(subdirsToAdd * (filesPerSubdir + 1))
          val subdirs = (1 to subdirsToAdd).map { i =>
            dir.resolve(s"subdir-$i")
          }
          val files = subdirs.flatMap { subdir =>
            (1 to filesPerSubdir).map { j =>
              subdir.resolve(s"file-$j")
            }
          }
          var allFiles = (subdirs ++ files).toSet
          val observer = Observers.apply[JPath](
            (_: Entry[JPath]) => creationLatch.countDown(),
            (_: Entry[JPath], _: Entry[JPath]) => {},
            (_: Entry[JPath]) => deletionLatch.countDown(),
            (_: JPath, _: IOException) => {}
          )
          usingAsync(FileCache.apply[JPath](identity, factory, observer)) { c =>
            c.reg(dir)
            executor.run(new Runnable {
              override def run(): Unit = {
                subdirs.foreach(Files.createDirectories(_))
                files.foreach(Files.createFile(_))
              }
            })
            creationLatch
              .waitFor(timeout) {
                val found = c.ls(dir).map(_.path).toSet
                // Need to synchronize since files is first set on a different thread
                allFiles.synchronized { found === allFiles }
              }
              .flatMap { _ =>
                executor.run(new Runnable {
                  override def run(): Unit = {
                    files.foreach(Files.deleteIfExists(_))
                    subdirs.foreach(Files.deleteIfExists(_))
                  }
                })
                deletionLatch
                  .waitFor(timeout) {
                    c.ls(dir) === Seq.empty
                  }
              }
              .andThen {
                case Failure(e) =>
                  println(s"Task failed $e")
                  if (creationLatch.getCount > 0) {
                    val count = creationLatch.getCount
                    10.milliseconds.sleep
                    val newCount = creationLatch.getCount
                    if (newCount == count)
                      println(s"$this Creation latch not triggered ($count)")
                    else
                      println(
                        s"$this Creation latch not triggered, but still being decremented $newCount")
                  }
                  if (deletionLatch.getCount > 0) {
                    val count = deletionLatch.getCount
                    10.milliseconds.sleep
                    val newCount = deletionLatch.getCount
                    if (newCount == count)
                      println(s"$this Deletion latch not triggered ($count)")
                    else
                      println(
                        s"$this Deletion latch not triggered, but still being decremented $newCount")
                  }
                  executor.close()
                case _ =>
                  executor.close()
              }
          }
        }
      }
    }
    'register - {
      'nonRecursive - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          val latch = new CountDownLatch(1)
          usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
            c.reg(dir, recursive = false)
            withTempFile(subdir) { f =>
              assert(f.exists)
              subdir.setLastModifiedTime(3000)
              latch.waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(subdir)
              }
            }
          }
        }
      }
      'depth - {
        'initial - {
          withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((_: Entry[JPath]) => {})) { c =>
                    c.register(dir, 1)
                    c.ls(dir) === Set(subdir, nestedSubdir)
                    c.register(dir, 2)
                    c.ls(dir) === Set(subdir, nestedSubdir, file)
                  }
                }
              }
            }
          }
        }
        'overlap - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempDirectory(subdir) { nestedSubdir =>
              withTempFile(nestedSubdir) { file =>
                val latch = new CountDownLatch(1)
                using(simpleCache((e: Entry[JPath]) =>
                  if (e.path.endsWith("deep")) latch.countDown())) { c =>
                  c.register(dir, 1)
                  c.ls(dir) === Set(subdir, nestedSubdir)
                  c.register(nestedSubdir, 0)
                  c.ls(dir) === Set(subdir, nestedSubdir, file)
                  val deep = Files.createDirectory(nestedSubdir.resolve("deep"))
                  val deepFile = Files.createFile(deep.resolve("file"))
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    while (!Files.exists(deepFile)) {}
                    val existing = FileOps.list(dir, true).asScala.map(_.toPath).toSet
                    existing === Set(subdir, nestedSubdir, file, deep, deepFile)
                    c.ls(dir) === Set(subdir, nestedSubdir, file, deep)
                  }
                }
              }
            }
          }
        }
        'holes - {
          'limit - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((_: Entry[JPath]) => {})) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(nestedSubdir, 0)
                    c.ls(dir) === Set(subdir)
                    c.ls(nestedSubdir) === Set(file)
                    val deep = Files.createDirectory(nestedSubdir.resolve("deep"))
                    val deepFile = Files.createFile(deep.resolve("file"))
                    c.register(nestedSubdir, 1)
                    c.ls(nestedSubdir) === Set(file, deep, deepFile)
                  }
                }
              }
            }
          }
          'unlimited - withTempDirectory { dir =>
            withTempDirectory(dir) { subdir =>
              withTempDirectory(subdir) { nestedSubdir =>
                withTempFile(nestedSubdir) { file =>
                  using(simpleCache((_: Entry[JPath]) => {})) { c =>
                    c.register(dir, 0)
                    c.ls(dir) === Set(subdir)
                    c.register(nestedSubdir, Integer.MAX_VALUE)
                    c.ls(dir) === Set(subdir)
                    c.ls(nestedSubdir) === Set(file)
                  }
                }
              }
            }
          }
        }
      }
      'recursive - {
        'initially - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'added - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
                c.reg(dir, recursive = false)
                c.ls(dir) === Set(subdir)
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
        'removed - withTempDirectory { dir =>
          withTempDirectory(dir) { subdir =>
            withTempFile(subdir) { f =>
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
                c.reg(dir)
                c.ls(dir) === Set(subdir, f)
                c.reg(dir, recursive = false)
                c.ls(dir) === Set(subdir, f)
              }
            }
          }
        }
      }
    }
    'cache - {
      'update - withTempFile { file =>
        val latch = new CountDownLatch(1)
        usingAsync(
          FileCache.apply[LastModified](
            LastModified(_),
            factory,
            new Observer[LastModified] {
              override def onCreate(newEntry: Entry[LastModified]): Unit = {}
              override def onDelete(oldEntry: Entry[LastModified]): Unit = {}
              override def onUpdate(oldEntry: Entry[LastModified],
                                    newEntry: Entry[LastModified]): Unit =
                if (oldEntry.value.lastModified != newEntry.value.lastModified) latch.countDown()
              override def onError(path: JPath, iOException: IOException): Unit = {}
            }
          )) { c =>
          c.reg(file.getParent, recursive = false)
          val cachedFile: Entry[LastModified] =
            c.ls(file.getParent, recursive = false) match {
              case Seq(f) if f.path == file => f
              case p                        => throw new IllegalStateException(p.toString)
            }
          val lastModified = cachedFile.value.lastModified
          lastModified ==> file.lastModified
          val updatedLastModified = 3000
          file.setLastModifiedTime(updatedLastModified)
          latch.waitFor(DEFAULT_TIMEOUT) {
            val newCachedFile: Entry[LastModified] =
              c.ls(file.getParent, recursive = false) match {
                case Seq(f) if f.path == file => f
                case p                        => throw new IllegalStateException(p.toString)
              }
            cachedFile.value.lastModified ==> lastModified
            newCachedFile.value.lastModified ==> updatedLastModified
          }
        }
      }
    }
    'symlinks - {
      'initial - withTempDirectory { dir =>
        withTempFile { file =>
          val latch = new CountDownLatch(1)
          Files.createSymbolicLink(dir.resolve("link"), file)
          usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
            c.reg(dir)
            Files.write(file, "foo".getBytes)
            latch.waitFor(DEFAULT_TIMEOUT) {
              new String(Files.readAllBytes(file)) ==> "foo"
            }
          }
        }
      }
      'directory - {
        'base - withTempDirectory { dir =>
          withTempDirectory { otherDir =>
            val file = Files.createFile(otherDir.resolve("file"))
            val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
            val latch = new CountDownLatch(1)
            usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
              c.reg(dir)
              Files.write(file, "foo".getBytes)
              latch.waitFor(DEFAULT_TIMEOUT) {
                new String(Files.readAllBytes(file)) ==> "foo"
              }
            }
          }
        }
        'nested - withTempDirectory { dir =>
          withTempDirectory { otherDir =>
            val subdir = Files.createDirectories(otherDir.resolve("subdir").resolve("nested"))
            val file = Files.createFile(subdir.resolve("file"))
            val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
            val linkedFile = link.resolve(otherDir.relativize(file))
            val latch = new CountDownLatch(1)
            usingAsync(
              simpleCache((e: Entry[JPath]) => if (e.path == linkedFile) latch.countDown())) { c =>
              c.reg(dir)
              Files.write(file, "foo".getBytes)
              latch.waitFor(DEFAULT_TIMEOUT) {
                new String(Files.readAllBytes(file)) ==> "foo"
              }
            }
          }
        }
        'loop - {
          'initial - withTempDirectory { dir =>
            withTempDirectory { otherDir =>
              Files.createSymbolicLink(dir.resolve("other"), otherDir)
              Files.createSymbolicLink(otherDir.resolve("dir"), dir)
              using(simpleCache((_: Entry[JPath]) => {})) { c =>
                intercept[FileSystemLoopException](c.reg(dir))
              }
            }
          }
          'added - {
            'original - {
              withTempDirectory { dir =>
                withTempDirectory { otherDir =>
                  Files.createSymbolicLink(otherDir.resolve("dir"), dir)
                  val latch = new CountDownLatch(1)
                  val observer = new LoopObserver(latch)
                  usingAsync(
                    FileCache.apply(((p: JPath) => p): Converter[JPath], factory, observer)) { c =>
                    c.reg(dir)
                    Files.createSymbolicLink(dir.resolve("other"), otherDir)
                    latch.waitFor(DEFAULT_TIMEOUT) {
                      c.ls(dir) === Seq.empty[JPath]
                    }
                  }
                }
              }
            }
            'symlink - {
              withTempDirectory { dir =>
                withTempDirectory { otherDir =>
                  val link = Files.createSymbolicLink(dir.resolve("other"), otherDir)
                  val latch = new CountDownLatch(1)
                  val observer = new LoopObserver(latch)
                  usingAsync(
                    FileCache.apply(((p: JPath) => p): Converter[JPath], factory, observer)) { c =>
                    c.reg(dir)
                    Files.createSymbolicLink(otherDir.resolve("dir"), dir)
                    latch.waitFor(DEFAULT_TIMEOUT) {
                      c.ls(dir) === Seq(link)
                    }
                  }
                }
              }
            }
          }
        }
        'added - withTempDirectory { dir =>
          withTempFile { file =>
            val linkLatch = new CountDownLatch(1)
            val fileLatch = new CountDownLatch(1)
            val link = Files.createSymbolicLink(dir.resolve("link"), file)
            usingAsync(simpleCache((e: Entry[JPath]) => {
              if (e.path.endsWith("dir-link")) linkLatch.countDown()
              if (e.path.endsWith("newfile")) fileLatch.countDown()
            })) { c =>
              c.reg(dir)
              val otherDir = file.getParent
              val foo = Files.createDirectories(otherDir.resolve("foo"))
              val dirLink = Files.createSymbolicLink(dir.resolve("dir-link"), otherDir)
              val fooLink = dirLink.resolve("foo")
              val newFile = foo.resolve("newfile")
              linkLatch
                .waitFor(DEFAULT_TIMEOUT) {
                  Files.write(newFile, "foo".getBytes)
                }
                .flatMap { _ =>
                  fileLatch.waitFor(DEFAULT_TIMEOUT) {
                    new String(Files.readAllBytes(newFile)) ==> "foo"
                    c.ls(dir) === Set(link,
                                      dirLink,
                                      dirLink.resolve(file.getFileName),
                                      fooLink,
                                      fooLink.resolve("newfile"))
                  }
                }
            }
          }
        }
      }
      'added - withTempDirectory { dir =>
        withTempFile { file =>
          val linkLatch = new CountDownLatch(1)
          val contentLatch = new CountDownLatch(1)
          usingAsync(simpleCache((_: Entry[JPath]) => {
            if (linkLatch.getCount == 1) linkLatch.countDown() else contentLatch.countDown()
          })) { c =>
            c.reg(dir)
            Files.createSymbolicLink(dir.resolve("link"), file)
            linkLatch
              .waitFor(DEFAULT_TIMEOUT) {
                Files.write(file, "foo".getBytes)
              }
              .flatMap { _ =>
                contentLatch.waitFor(DEFAULT_TIMEOUT) {
                  new String(Files.readAllBytes(file)) ==> "foo"
                }
              }
          }
        }
      }
      'removed - withTempDirectory { dir =>
        withTempFile { file =>
          val linkLatch = new CountDownLatch(1)
          val contentLatch = new CountDownLatch(1)
          usingAsync(simpleCache((e: Entry[JPath]) => {
            if (linkLatch.getCount == 1) linkLatch.countDown() else contentLatch.countDown()
          })) { c =>
            val link = Files.createSymbolicLink(dir.resolve("link"), file)
            c.reg(dir)
            c.ls(dir) === Seq(link)
            assert(Files.isRegularFile(link))
            Files.deleteIfExists(file)
            linkLatch
              .waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(link)
                assert(!Files.isRegularFile(link))
              }
          }
        }
      }
      'multiple - {
        'static - withTempDirectory { dir =>
          val latch = new CountDownLatch(2)
          val paths: mutable.Set[JPath] = mutable.Set.empty[JPath]
          withTempDirectory { otherDir =>
            withTempFile { file =>
              val link = Files.createSymbolicLink(dir.resolve("link"), file)
              val otherLink = Files.createSymbolicLink(otherDir.resolve("link"), file)
              usingAsync(FileCache.apply[JPath](
                identity,
                new Observer[JPath] {
                  override def onCreate(newEntry: Entry[JPath]): Unit = {}
                  override def onDelete(oldEntry: Entry[JPath]): Unit = {}
                  override def onUpdate(oldEntry: Entry[JPath], newEntry: Entry[JPath]): Unit = {
                    if (paths.add(newEntry.path)) latch.countDown()
                  }
                  override def onError(path: JPath, exception: IOException): Unit = {}
                }
              )) { c =>
                c.register(dir)
                c.register(otherDir)
                Files.write(file, "foo".getBytes)
                latch.waitFor(DEFAULT_TIMEOUT) {
                  paths.toSet === Set(link, otherLink)
                }
              }
            }
          }
        }
        'removed - withTempDirectory { dir =>
          val updateLatch = new CountDownLatch(1)
          val deletionLatch = new CountDownLatch(1)
          val secondDeletionLatch = new CountDownLatch(2)
          val secondUpdateLatch = new CountDownLatch(1)
          val paths: mutable.Set[JPath] = mutable.Set.empty[JPath]
          var closed = false
          withTempDirectory { otherDir =>
            withTempFile { file =>
              val link = Files.createSymbolicLink(dir.resolve("link"), file)
              val otherLink = Files.createSymbolicLink(otherDir.resolve("link"), file)
              usingAsync(FileCache.apply[JPath](
                identity,
                new Observer[JPath] {
                  override def onCreate(newEntry: Entry[JPath]): Unit = {}

                  override def onDelete(oldEntry: Entry[JPath]): Unit = {
                    deletionLatch.countDown()
                    secondDeletionLatch.countDown()
                  }

                  override def onUpdate(oldEntry: Entry[JPath], newEntry: Entry[JPath]): Unit = {
                    if (newEntry.path.endsWith("link")) {
                      paths.add(newEntry.path)
                      updateLatch.countDown()
                    } else if (closed) {
                      secondUpdateLatch.countDown()
                    }
                  }
                  override def onError(path: JPath, exception: IOException): Unit = {}
                }
              )) { c =>
                c.register(dir)
                c.register(otherDir)
                Files.delete(otherLink)
                deletionLatch
                  .waitFor(DEFAULT_TIMEOUT) {
                    c.ls(otherDir) === Seq.empty[JPath]
                  }
                  .flatMap { _ =>
                    Files.write(file, "foo".getBytes)
                    updateLatch
                      .waitFor(DEFAULT_TIMEOUT) {
                        paths.toSet === Set(link)
                      }
                      .flatMap { _ =>
                        Files.delete(link)
                        secondDeletionLatch
                          .waitFor(DEFAULT_TIMEOUT) {
                            closed = true
                          }
                          .flatMap { _ =>
                            Files.write(file, "bar".getBytes)
                            secondUpdateLatch.waitFor(5.millis) {
                              // should be unreachable
                              throw new IllegalStateException("Unmonitored file triggered callback")
                              ()
                            }
                          }
                          .recover {
                            case _: TimeoutException => ()
                          }
                      }
                  }
                  .andThen {
                    case Failure(_) =>
                      println(
                        s"Test failed:\ndeletionLatch = ${deletionLatch.getCount}\n" +
                          s"secondDeletionLatch = ${secondDeletionLatch.getCount}\n"
                          + s"updateLatch = ${updateLatch.getCount}\n"
                          + s"secondUpdateLatch = ${secondUpdateLatch.getCount}")
                  }
              }
            }
          }
        }
      }
    }
    'addCallback - withTempDirectory { dir =>
      usingAsync(simpleCache((_: Entry[JPath]) => {})) { c =>
        val creationLatch = new CountDownLatch(1)
        val updateLatch = new CountDownLatch(1)
        val deletionLatch = new CountDownLatch(1)
        c.addCallback(new OnChange[JPath] {
          override def apply(entry: Entry[JPath]): Unit = {
            creationLatch.countDown()
            if (entry.path.endsWith("file") && !Files.exists(entry.path)) deletionLatch.countDown()
            else if (Files.getLastModifiedTime(entry.path).toMillis == 3000)
              updateLatch.countDown()
          }
        })
        c.reg(dir)
        val file = Files.createFile(dir.resolve("file"))
        creationLatch
          .waitFor(DEFAULT_TIMEOUT) {
            c.ls(dir) === Seq(file)
            Files.setLastModifiedTime(file, FileTime.fromMillis(3000))
          }
          .flatMap { _ =>
            updateLatch
              .waitFor(DEFAULT_TIMEOUT) {
                Files.delete(file)
              }
              .flatMap { _ =>
                deletionLatch.waitFor(DEFAULT_TIMEOUT) {
                  c.ls(dir) === Seq.empty[JPath]
                }
              }
          }
      }
    }
    'removeObserver - withTempDirectory { dir =>
      val latch = new CountDownLatch(1)
      var secondObserverFired = false
      usingAsync(simpleCache((_: Entry[JPath]) => latch.countDown())) { c =>
        val handle = c.addCallback(new Directory.OnChange[JPath] {
          override def apply(entry: Entry[JPath]): Unit = secondObserverFired = true
        })
        c.reg(dir)
        c.removeObserver(handle)
        val file = Files.createFile(dir.resolve("file"))
        latch.waitFor(DEFAULT_TIMEOUT) {
          assert(!secondObserverFired)
          c.ls(dir) === Seq(file)
        }
      }
    }
  }
}

object FileCacheTest {
  implicit class FileCacheOps[T <: AnyRef](val fileCache: FileCache[T]) extends AnyVal {
    def ls(dir: JPath,
           recursive: Boolean = true,
           filter: EntryFilter[_ >: T] = EntryFilters.AllPass): Seq[Entry[T]] =
      fileCache.list(dir, recursive, filter).asScala
    def reg(dir: JPath, recursive: Boolean = true): Directory[T] =
      fileCache.register(dir, recursive)
  }
}

object DefaultFileCacheTest extends FileCacheTest {
  val factory = DirectoryWatcher.DEFAULT_FACTORY
  val tests = testsImpl
}

object NioFileCacheTest extends FileCacheTest {
  val factory = new DirectoryWatcher.Factory {
    override def create(callback: DirectoryWatcher.Callback): DirectoryWatcher =
      new NioDirectoryWatcher(callback)
  }
  val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests('ignore - {
        println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
