package com
package swoval
package files

import java.io.IOException
import java.nio.file.{ Path, Paths }

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
      implicit val logger: TestLogger = new CachingLogger
      withTempFile { file =>
        val latch = new CountDownLatch(1)
        val link = dir.resolve("link") linkTo file
        usingAsync(simpleCache((e: Entry[Path]) => if (e.path == link) latch.countDown())) { c =>
          c.reg(dir)
          file write "foo"
          latch.waitFor(DEFAULT_TIMEOUT) {
            file.read ==> "foo"
          }
        }
      }
    }
    'file - withTempDirectory { dir =>
      implicit val logger: TestLogger = new CachingLogger
      withTempFile { file =>
        val latch = new CountDownLatch(1)
        val link = dir.resolve("link") linkTo file
        usingAsync(simpleCache((e: Entry[Path]) => if (e.path == link) latch.countDown())) { c =>
          c.reg(link)
          file write "foo"

          latch.waitFor(DEFAULT_TIMEOUT) {
            file.read ==> "foo"
          }
        }
      }
    }
    'directory - {
      'base - withTempDirectory { root =>
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("directory_base").createDirectories()
        withTempDirectory { otherDir =>
          val file = otherDir.resolve("file").createFile()
          val link = dir.resolve("link") linkTo otherDir
          val latch = new CountDownLatch(1)
          usingAsync(simpleCache((e: Entry[Path]) =>
            if (e.path == link.resolve("file")) latch.countDown())) { c =>
            c.reg(dir)
            file write "foo"
            latch.waitFor(DEFAULT_TIMEOUT) {
              file.read ==> "foo"
            }
          }
        }
      }
      'nested - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory { otherDir =>
          val subdir = otherDir.resolve("subdir").resolve("nested").createDirectories()
          val file = subdir.resolve("file").createFile()
          val link = dir.resolve("link") linkTo otherDir
          val linkedFile = link.resolve(otherDir.relativize(file))
          val latch = new CountDownLatch(1)
          usingAsync(simpleCache((e: Entry[Path]) => if (e.path == linkedFile) latch.countDown())) {
            c =>
              c.reg(dir)
              file write "foo"
              latch.waitFor(DEFAULT_TIMEOUT) {
                file.read ==> "foo"
              }
          }
        }
      }
      'link - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory { otherDir =>
          val link = otherDir.resolve("link") linkTo dir
          val file = dir.resolve("file").createFile()
          val latch = new CountDownLatch(1)
          usingAsync(lastModifiedCache((e: Entry[LastModified]) =>
            if (e.value.lastModified == 3000) latch.countDown())) {
            c: FileTreeRepository[LastModified] =>
              c.register(link, Integer.MAX_VALUE)
              c.ls(link) === Set(link.resolve("file"))
              file.setLastModifiedTime(3000)
              latch.waitFor(DEFAULT_TIMEOUT) {}
          }
        }
      }
      'loop - {
        'initial - withTempDirectory { dir =>
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory { otherDir =>
            dir.resolve("other") linkTo otherDir
            otherDir.resolve("dir") linkTo dir
            using(simpleCache((_: Entry[Path]) => {})) { c =>
              c.register(dir, Integer.MAX_VALUE)
              c.ls(dir) == Set(dir.resolve("other"), dir.resolve("other").resolve("dir"))
            }
          }
        }
        'added - {
          'original - {
            withTempDirectory { root =>
              implicit val logger: TestLogger = new CachingLogger
              val dir = root.resolve("original").createDirectories()
              withTempDirectory { otherDir =>
                otherDir.resolve("dir") linkTo dir
                val latch = new CountDownLatch(1)
                val loopLink = dir.resolve("other")
                usingAsync(simpleCache((e: Entry[Path]) =>
                  if (e.path == loopLink.resolve("dir")) latch.countDown())) { c =>
                  c.reg(dir)
                  loopLink linkTo otherDir
                  latch.waitFor(DEFAULT_TIMEOUT) {
                    c.ls(dir) === Set(loopLink, loopLink.resolve("dir"))
                  }
                }
              }
            }
          }
          'symlink - {
            implicit val logger: TestLogger = new CachingLogger
            withTempDirectory { root =>
              val dir = root.resolve("symlink").createDirectories()
              withTempDirectory { otherDir =>
                val link = dir.resolve("other") linkTo otherDir
                val latch = new CountDownLatch(1)
                val loopLink = otherDir.resolve("dir")
                val loop = dir.resolve("other").resolve("dir")
                usingAsync(simpleCache((e: Entry[Path]) => if (e.path == loop) latch.countDown())) {
                  c =>
                    c.reg(dir)
                    loopLink linkTo dir
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
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("directory_base").createDirectories()
        withTempDirectory { otherRoot =>
          val otherDir = otherRoot.resolve("directory_link").createDirectory()
          val file = otherDir.resolve("file").createFile()
          val linkLatch = new CountDownLatch(1)
          val fileLatch = new CountDownLatch(1)
          val link = dir.resolve("link") linkTo file
          val foo = otherDir.resolve("foo")
          val dirLink = dir.resolve("dir-link")
          usingAsync(simpleCache((e: Entry[Path]) => {
            if (e.getTypedPath.getPath.endsWith("dir-link")) linkLatch.countDown()
            if (e.getTypedPath.getPath.endsWith("newfile")) fileLatch.countDown()
          })) { c =>
            c.reg(dir)
            val otherDir = file.getParent
            foo.createDirectories()
            dirLink linkTo otherDir
            val fooLink = dirLink.resolve("foo")
            val newFile = foo.resolve("newfile")
            linkLatch
              .waitFor(DEFAULT_TIMEOUT) {
                newFile write "foo"
              }
              .flatMap { _ =>
                fileLatch.waitFor(DEFAULT_TIMEOUT) {
                  newFile.read ==> "foo"
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
      implicit val logger: TestLogger = new CachingLogger
      withTempFile { file =>
        val linkLatch = new CountDownLatch(1)
        val contentLatch = new CountDownLatch(1)
        val link = dir.resolve("link")
        usingAsync(simpleCache((e: Entry[Path]) => {
          if (e.path == link)
            if (linkLatch.getCount == 1) linkLatch.countDown() else contentLatch.countDown()
        })) { c =>
          c.reg(dir)
          link linkTo file
          linkLatch
            .waitFor(DEFAULT_TIMEOUT) {
              file write "foo"
            }
            .flatMap { _ =>
              contentLatch.waitFor(DEFAULT_TIMEOUT) {
                file.read ==> "foo"
              }
            }
        }
      }
    }
    'removed - {
      'file - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempFile { file =>
          val linkLatch = new CountDownLatch(1)
          usingAsync(simpleCache((e: Entry[Path]) => {
            if (linkLatch.getCount == 1) linkLatch.countDown()
          })) { c =>
            val link = dir.resolve("link") linkTo file
            c.reg(dir)
            c.ls(dir) === Seq(link)
            assert(link.isRegularFile())
            file.delete()
            linkLatch
              .waitFor(DEFAULT_TIMEOUT) {
                c.ls(dir) === Seq(link)
                assert(!link.isRegularFile())
              }
          }
        }
      }
      'link - withTempDirectory { root =>
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("remove-link").createDirectories()
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
            link linkTo file
            c.reg(dir)
            c.ls(dir) === Seq(link)
            assert(link.isRegularFile())
            file.delete()
            link.delete()
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
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("no-follow").createDirectories()
        withTempDirectory { otherDir =>
          val file = otherDir.resolve("file").createFile()
          file write "foo"
          val link = dir.resolve("link") linkTo otherDir
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
        implicit val logger: TestLogger = new CachingLogger
        val dir = root.resolve("no-follow").createDirectories()
        withTempDirectory { otherDir =>
          val file = otherDir.resolve("updated-file").createFile()
          file write "foo"
          val creationLatch = new CountDownLatch(1)
          val deletionLatch = new CountDownLatch(1)
          val link = dir.resolve("link") linkTo otherDir
          usingAsync(FileTreeRepositories.get[Path](identity, false)) { c =>
            c.register(dir, Integer.MAX_VALUE)
            c.addCacheObserver(new CacheObserver[Path] {
              override def onCreate(newEntry: Entry[Path]): Unit =
                if (newEntry.path == link) creationLatch.countDown()
              override def onDelete(oldEntry: Entry[Path]): Unit =
                if (oldEntry.path == link) deletionLatch.countDown()
              override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {}
              override def onError(exception: IOException): Unit = {}
            })
            link.delete()
            deletionLatch.waitFor(DEFAULT_TIMEOUT) {
              link linkTo otherDir
            }
            creationLatch.waitFor(DEFAULT_TIMEOUT) {
              c.ls(dir, true, functional.Filters.AllPass) === Set(link)
            }
          }.flatMap { _ =>
            using(FileTreeRepositories.get(identity, true)) { c =>
              c.register(dir)
              c.ls(dir, true, functional.Filters.AllPass) === Set(link,
                                                                  link.resolve("updated-file"))
            }
          }
        }
      }
    }
    'multiple - {
      'static - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        val latch = new CountDownLatch(2)
        val paths: mutable.Set[Path] = mutable.Set.empty[Path]
        withTempDirectory { otherDir =>
          withTempFile { file =>
            val link = dir.resolve("link") linkTo file
            val otherLink = otherDir.resolve("link") linkTo file
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
              file write "foo"
              latch.waitFor(DEFAULT_TIMEOUT) {
                paths.toSet === Set(link, otherLink)
              }
            }
          }
        }
      }
      'removed - withTempDirectory { root =>
        implicit val logger: TestLogger = new CachingLogger
        val name = FileCacheSymlinkTest.this.getClass.getSimpleName
        val dir = root.resolve(name).resolve("directory-base").createDirectories()
        val updateLatch = new CountDownLatch(1)
        val deletionLatch = new CountDownLatch(1)
        val secondDeletionLatch = new CountDownLatch(1)
        val secondUpdateLatch = new CountDownLatch(1)
        val paths: mutable.Set[Path] = mutable.Set.empty[Path]
        var closed = false
        withTempDirectory { otherRoot =>
          val otherDir = otherRoot.resolve(name).resolve("directory-other").createDirectories()
          withTempFile { file =>
            val link = dir.resolve("link") linkTo file
            val otherLink = otherDir.resolve("link") linkTo file
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
              otherLink.delete()
              deletionLatch
                .waitFor(DEFAULT_TIMEOUT) {
                  c.ls(otherDir) === Seq.empty[Path]
                }
                .flatMap { _ =>
                  file write "foo"
                  updateLatch
                    .waitFor(DEFAULT_TIMEOUT) {
                      paths.toSet === Set(link)
                    }
                    .flatMap { _ =>
                      link.delete()
                      secondDeletionLatch
                        .waitFor(DEFAULT_TIMEOUT) {
                          closed = true
                        }
                        .flatMap { _ =>
                          file write "bar"
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
        if (swoval.test.verbose)
          println("Not running NioFileCacheTest on platform other than the jvm on osx")
      })
}
