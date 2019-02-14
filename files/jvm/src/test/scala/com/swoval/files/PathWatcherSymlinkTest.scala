package com
package swoval
package files

import java.nio.file.Files

import com.swoval.runtime.Platform
import com.swoval.files.test._
import com.swoval.test._
import utest._
import TestHelpers._

trait PathWatcherSymlinkTest extends TestSuite {
  def defaultWatcher(callback: PathWatchers.Event => _)(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event]
  val testsImpl = Tests {
    'follow - {
      'file - {
        'initial - {
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory { dir =>
            withTempFile { file =>
              val link = Files.createSymbolicLink(dir.resolve("link"), file)
              val latch = new CountDownLatch(1)
              usingAsync(defaultWatcher((e: PathWatchers.Event) => {
                if (e.getTypedPath.getPath == link) {
                  latch.countDown()
                }
              })) { c =>
                assert(c.register(dir, Integer.MAX_VALUE).isRight())
                Files.write(file, "foo".getBytes)
                latch.waitFor(DEFAULT_TIMEOUT) {
                  new String(Files.readAllBytes(file)) ==> "foo"
                }
              }
            }
          }
        }
        'added - {
          implicit val logger: TestLogger = new CachingLogger
          withTempDirectory { dir =>
            withTempFile { file =>
              val latch = new CountDownLatch(1)
              val link = dir.resolve("link")
              usingAsync(defaultWatcher((e: PathWatchers.Event) => {
                if (e.getTypedPath.getPath == link) {
                  latch.countDown()
                }
              })) { c =>
                assert(c.register(dir, Integer.MAX_VALUE).isRight())
                Files.createSymbolicLink(link, file)
                Files.write(file, "foo".getBytes)
                latch.waitFor(DEFAULT_TIMEOUT) {
                  new String(Files.readAllBytes(file)) ==> "foo"
                }
              }
            }
          }
        }
      }
      'directory - withTempDirectory { dir =>
        implicit val logger: TestLogger = new CachingLogger
        withTempDirectory { otherDir =>
          val file = otherDir.resolve("file").createFile()
          val latch = new CountDownLatch(1)
          val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          val linkedFile = link.resolve("file")
          usingAsync(defaultWatcher((e: PathWatchers.Event) => {
            if (e.getTypedPath.getPath == linkedFile) {
              latch.countDown()
            }
          })) { c =>
            assert(c.register(dir, Integer.MAX_VALUE).isRight())
            Files.write(file, "foo".getBytes)
            latch.waitFor(DEFAULT_TIMEOUT) {
              new String(Files.readAllBytes(file)) ==> "foo"
            }
          }
        }
      }
    }
  }
}

object PathWatcherSymlinkTest extends PathWatcherSymlinkTest {
  override def defaultWatcher(callback: Predef.Function[PathWatchers.Event, _])(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event] = {
    val res = PathWatchers.get(true, new DirectoryRegistryImpl(), testLogger)
    res.addObserver(callback)
    res
  }
  override val tests = testsImpl
}
object NioPathWatcherSymlinkTest extends PathWatcherSymlinkTest {
  override def defaultWatcher(callback: Predef.Function[PathWatchers.Event, _])(
      implicit testLogger: TestLogger): PathWatcher[PathWatchers.Event] = {
    val res = PlatformWatcher.make(true, new DirectoryRegistryImpl(), testLogger)
    res.addObserver(callback)
    res
  }
  override val tests = if (Platform.isMac && Platform.isJVM) {
    testsImpl
  } else {
    Tests {
      'ignore - {
        if (swoval.test.verbose)
          println("Not running NioPathWatcherSymlinkTest on platform other than osx on the jvm")
      }
    }
  }
}
