package com.swoval
package files

import java.io.IOException
import java.nio.file.{ Files, Path, Paths }

import FileTreeDataViews.Entry
import com.swoval.files.FileCacheTest.FileCacheOps
import com.swoval.files.test._
import com.swoval.runtime.Platform
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.collection.mutable
import scala.concurrent.{ Future, TimeoutException }
import scala.concurrent.duration._
import scala.util.Failure
import TestHelpers._
import EntryOps._
import com.swoval.files.FileTreeDataViews.CacheObserver

trait FileCacheSymlinkTest extends TestSuite with FileCacheTest {
  val testsImpl = Tests {
    'initial - withTempDirectory { dir =>
      withTempFile { file =>
        val latch = new CountDownLatch(1)
        Files.createSymbolicLink(dir.resolve("link"), file)
        usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
          c.reg(dir)
          Files.write(file, "foo".getBytes)
          latch.waitFor(DEFAULT_TIMEOUT) {
            new String(Files.readAllBytes(file)) ==> "foo"
          }
        }
      }
    }
    'file - withTempDirectory { dir =>
      withTempFile { file =>
        val latch = new CountDownLatch(1)
        val link = Files.createSymbolicLink(dir.resolve("link"), file)
        usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
          c.reg(link)
          Files.write(file, "foo".getBytes)
          latch.waitFor(DEFAULT_TIMEOUT) {
            new String(Files.readAllBytes(file)) ==> "foo"
          }
        }
      }
    }
    'directory - {
      'base - withTempDirectory { root =>
        val dir = Files.createDirectories(root.resolve("directory_base"));
        withTempDirectory { otherDir =>
          val file = Files.createFile(otherDir.resolve("file"))
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          val latch = new CountDownLatch(1)
          usingAsync(simpleCache((_: Entry[Path]) => latch.countDown())) { c =>
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
          usingAsync(simpleCache((e: Entry[Path]) =>
            if (e.getTypedPath.getPath == linkedFile) latch.countDown())) { c =>
            c.reg(dir)
            Files.write(file, "foo".getBytes)
            latch.waitFor(DEFAULT_TIMEOUT) {
              new String(Files.readAllBytes(file)) ==> "foo"
            }
          }
        }
      }
      'link - withTempDirectory { dir =>
        withTempDirectory { otherDir =>
          val link = Files.createSymbolicLink(otherDir.resolve("link"), dir)
          val file = dir.resolve("file").createFile()
          val latch = new CountDownLatch(1)
          usingAsync(lastModifiedCache((e: Entry[LastModified]) => {
            if (e.getValue.get.lastModified == 3000) latch.countDown()
          })) { c: FileTreeRepository[LastModified] =>
            c.register(link, Integer.MAX_VALUE)
            c.ls(link) === Set(link.resolve("file"))
            file.setLastModifiedTime(3000)
            latch.waitFor(DEFAULT_TIMEOUT) {}
          }
        }
      }
      'loop - {
        'initial - withTempDirectory { dir =>
          withTempDirectory { otherDir =>
            Files.createSymbolicLink(dir.resolve("other"), otherDir)
            val link = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
            using(simpleCache((_: Entry[Path]) => {})) { c =>
              c.register(dir, Integer.MAX_VALUE)
              c.ls(dir) == Set(dir.resolve("other"), dir.resolve("other").resolve("dir"))
            }
          }
        }
        'added - {
          'original - {
            withTempDirectory { root =>
              val dir = Files.createDirectory(root.resolve("original"))
              withTempDirectory { otherDir =>
                Files.createSymbolicLink(otherDir.resolve("dir"), dir)
                val latch = new CountDownLatch(1)
                val loopLink = dir.resolve("other")
                usingAsync(simpleCache((e: Entry[Path]) => {
                  if (e.getTypedPath.getPath == loopLink.resolve("dir")) {
                    latch.countDown()
                  }
                })) { c =>
                  c.reg(dir)
                  Files.createSymbolicLink(loopLink, otherDir)
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Set(loopLink, loopLink.resolve("dir"))
                  }
                }
              }
            }
          }
          'symlink - {
            withTempDirectory { root =>
              val dir = Files.createDirectory(root.resolve("symlink"))
              withTempDirectory { otherDir =>
                val link = Files.createSymbolicLink(dir.resolve("other"), otherDir)
                val latch = new CountDownLatch(1)
                usingAsync(simpleCache((e: Entry[Path]) => {
                  if (e.getTypedPath.getPath.endsWith("dir")) {
                    latch.countDown()
                  }
                })) { c =>
                  c.reg(dir)
                  val loopLink = Files.createSymbolicLink(otherDir.resolve("dir"), dir)
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Set(link, link.resolve("dir"))
                  }
                }
              }
            }
          }
        }
      }
      'newLink - withTempDirectory { root =>
        val dir = Files.createDirectories(root.resolve("directory_base"))
        withTempDirectory { otherRoot =>
          val otherDir = Files.createDirectory(otherRoot.resolve("directory_link"))
          val file = otherDir.resolve("file").createFile()
          val linkLatch = new CountDownLatch(1)
          val fileLatch = new CountDownLatch(1)
          val link = Files.createSymbolicLink(dir.resolve("link"), file)
          usingAsync(simpleCache((e: Entry[Path]) => {
            if (e.getTypedPath.getPath.endsWith("dir-link")) linkLatch.countDown()
            if (e.getTypedPath.getPath.endsWith("newfile")) fileLatch.countDown()
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
    'created - withTempDirectory { dir =>
      withTempFile { file =>
        val linkLatch = new CountDownLatch(1)
        val contentLatch = new CountDownLatch(1)
        usingAsync(simpleCache((_: Entry[Path]) => {
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
    'removed - {
      'file - withTempDirectory { dir =>
        withTempFile { file =>
          val linkLatch = new CountDownLatch(1)
          usingAsync(simpleCache((e: Entry[Path]) => {
            if (linkLatch.getCount == 1) linkLatch.countDown()
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
      'link - withTempDirectory { root =>
        val dir = Files.createDirectory(root.resolve("remove-link"))
        withTempFile { file =>
          val linkLatch = new CountDownLatch(1)
          val link = dir.resolve("link")
          usingAsync(FileCacheTest.getCached[Path](
            true,
            identity,
            new FileTreeDataViews.CacheObserver[Path] {
              override def onCreate(newEntry: Entry[Path]): Unit = {}

              override def onDelete(oldEntry: Entry[Path]): Unit = {
                if (oldEntry.getTypedPath.getPath == link) linkLatch.countDown()
              }

              override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {}

              override def onError(exception: IOException): Unit = {}
            }
          )) { c =>
            Files.createSymbolicLink(link, file)
            c.reg(dir)
            c.ls(dir) === Seq(link)
            assert(Files.isRegularFile(link))
            Files.deleteIfExists(file)
            Files.deleteIfExists(link)
            linkLatch
              .waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq.empty[Path]
              }
          }
        }
      }
    }
    'noFollow - {
      'initially - withTempDirectory { root =>
        val dir = Files.createDirectory(root.resolve("no-follow"))
        withTempDirectory { otherDir =>
          val file = otherDir.resolve("file").createFile()
          Files.write(file, "foo".getBytes)
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          Future.sequence(
            Seq(
              using(FileTreeRepositories.get(identity, false)) { c =>
                c.register(dir, Integer.MAX_VALUE)
                c.ls(dir, true, functional.Filters.AllPass) === Set(link)
              },
              using(FileTreeRepositories.get(identity, true)) { c =>
                c.register(dir)
                c.ls(dir, true, functional.Filters.AllPass) === Set(link, link.resolve("file"))
              }
            )
          )
        }
      }
      'updated - withTempDirectory { root =>
        val dir = Files.createDirectory(root.resolve("no-follow"))
        withTempDirectory { otherDir =>
          val file = otherDir.resolve("file").createFile()
          Files.write(file, "foo".getBytes)
          val latch = new CountDownLatch(1)
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          usingAsync(FileTreeRepositories.get[Path](identity, false)) { c =>
            c.register(dir, Integer.MAX_VALUE)
            c.addCacheObserver(new CacheObserver[Path] {
              override def onCreate(newEntry: Entry[Path]): Unit =
                if (newEntry.getTypedPath.getPath == link) latch.countDown()
              override def onDelete(oldEntry: Entry[Path]): Unit = {}
              override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {
                if (newEntry.getTypedPath.getPath == link) latch.countDown()
              }
              override def onError(exception: IOException): Unit = {}
            })
            link.delete()
            Files.createSymbolicLink(link, otherDir)
            latch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir, true, functional.Filters.AllPass) === Set(link)
            }
          }.flatMap { _ =>
            using(FileTreeRepositories.get(identity, true)) { c =>
              c.register(dir)
              c.ls(dir, true, functional.Filters.AllPass) === Set(link, link.resolve("file"))
            }
          }
        }
      }
    }
    'multiple - {
      'static - withTempDirectory { dir =>
        val latch = new CountDownLatch(2)
        val paths: mutable.Set[Path] = mutable.Set.empty[Path]
        withTempDirectory { otherDir =>
          withTempFile { file =>
            val link = Files.createSymbolicLink(dir.resolve("link"), file)
            val otherLink = Files.createSymbolicLink(otherDir.resolve("link"), file)
            usingAsync(FileCacheTest.getCached[Path](
              true,
              identity,
              new FileTreeDataViews.CacheObserver[Path] {
                override def onCreate(newEntry: Entry[Path]): Unit = {}
                override def onDelete(oldEntry: Entry[Path]): Unit = {}
                override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {
                  val path = newEntry.getTypedPath.getPath
                  if (path.getFileName == Paths.get("link") && paths.add(path)) latch.countDown()
                }
                override def onError(exception: IOException): Unit = {}
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
      'removed - withTempDirectory { root =>
        val name = FileCacheSymlinkTest.this.getClass.getSimpleName
        val dir = Files.createDirectories(root.resolve(name).resolve("directory-base"))
        val updateLatch = new CountDownLatch(1)
        val deletionLatch = new CountDownLatch(1)
        val secondDeletionLatch = new CountDownLatch(1)
        val secondUpdateLatch = new CountDownLatch(1)
        val paths: mutable.Set[Path] = mutable.Set.empty[Path]
        var closed = false
        withTempDirectory { otherRoot =>
          val otherDir = Files.createDirectories(otherRoot.resolve(name).resolve("directory-other"))
          withTempFile { file =>
            val link = Files.createSymbolicLink(dir.resolve("link"), file)
            val otherLink = Files.createSymbolicLink(otherDir.resolve("link"), file)
            usingAsync(FileCacheTest.getCached[Path](
              true,
              identity,
              new FileTreeDataViews.CacheObserver[Path] {
                override def onCreate(newEntry: Entry[Path]): Unit = {}

                override def onDelete(oldEntry: Entry[Path]): Unit = {
                  if (oldEntry.getTypedPath.getPath == otherLink) {
                    deletionLatch.countDown()
                  }
                  if (oldEntry.getTypedPath.getPath == link) secondDeletionLatch.countDown()
                }

                override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {
                  if (newEntry.getTypedPath.getPath == link) {
                    paths.add(newEntry.getTypedPath.getPath)
                    updateLatch.countDown()
                  } else if (closed && newEntry.getTypedPath.getPath.startsWith(link)) {
                    secondUpdateLatch.countDown()
                  }
                }

                override def onError(exception: IOException): Unit = {}
              }
            )) { c =>
              c.register(dir)
              c.register(otherDir)
              Files.delete(otherLink)
              deletionLatch
                .waitFor(DEFAULT_TIMEOUT) {
                  c.ls(otherDir) === Seq.empty[Path]
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
                          secondUpdateLatch
                            .waitFor(100.millis) {
                              // should be unreachable
                              throw new IllegalStateException("Unmonitored file triggered callback")
                              ()
                            }
                            .recover {
                              case _: TimeoutException => ()
                            }
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
}
object FileCacheSymlinkTest extends FileCacheSymlinkTest with DefaultFileCacheTest {
  val tests = testsImpl
}
object NioFileCacheSymlinkTest extends FileCacheSymlinkTest with NioFileCacheTest {
  override val tests =
    if (Platform.isJVM && Platform.isMac) testsImpl
    else
      Tests('ignore - {
        println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
