package com.swoval.files.apple

import java.nio.file.Files

import com.swoval.files.test.{ CountDownLatch, _ }
import com.swoval.test._
import utest._
import utest.framework.ExecutionContext.RunNow

object FileEventApiTest extends TestSuite {

  def getFileEventsApi(onFileEvent: FileEvent => Unit,
                       onStreamClosed: String => Unit = _ => {}): FileEventsApi =
    FileEventsApi.apply((fe: FileEvent) => onFileEvent(fe), (s: String) => onStreamClosed(s))

  val tests: Tests = testOn(MacOS) {
    'register - withTempDirectory { dir =>
      val latch = new CountDownLatch(1)
      val file = dir.resolve("file")
      val api = getFileEventsApi(fe => {
        assert(fe.fileName.startsWith(dir.toString))
        Files.deleteIfExists(file)
        latch.countDown()
      })
      api.createStream(dir.toString, 0.05, new Flags.Create().setNoDefer().getValue)
      Files.createFile(file)

      latch
        .waitFor(DEFAULT_TIMEOUT) {}
        .andThen {
          case _ => api.close()
        }(RunNow)
    }
    'removeStream - withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        val latch = new CountDownLatch(1)
        val api = getFileEventsApi(_ => {}, s => {
          assert(s == subdir.toString)
          latch.countDown()
        })
        api.createStream(subdir.toString, 0.05, new Flags.Create().setNoDefer().getValue)
        api.createStream(dir.toString, 0.05, new Flags.Create().setNoDefer().getValue)
        latch
          .waitFor(DEFAULT_TIMEOUT) {}
          .andThen {
            case _ => api.close()
          }(RunNow)
      }
    }
    'close - {
      val api = getFileEventsApi(_ => {})
      api.close()
      api.close()
    }
  }
}
