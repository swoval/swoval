package com.swoval.files.apple

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import com.swoval.files.test.{ CountDownLatch, _ }
import com.swoval.test._
import utest._
import utest.framework.ExecutionContext.RunNow

object FileEventMonitorTest extends TestSuite {

  def getFileEventsApi(onFileEvent: FileEvent => Unit,
                       onStreamClosed: String => Unit = _ => {}): FileEventMonitor =
    FileEventMonitors.get((fe: FileEvent) => onFileEvent(fe), (s: String) => onStreamClosed(s))

  val tests: Tests = testOn(MacOS) {
    'register - withTempDirectory { dir =>
      val latch = new CountDownLatch(1)
      val file = dir.resolve("file")
      val api = getFileEventsApi(fe => {
        assert(fe.fileName.startsWith(dir.toString))
        Files.deleteIfExists(file)
        latch.countDown()
      })
      api.createStream(dir, 50, TimeUnit.MILLISECONDS, new Flags.Create().setNoDefer())
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
        val handle =
          api.createStream(subdir, 50, TimeUnit.MICROSECONDS, new Flags.Create().setNoDefer())
        api.createStream(dir, 50, TimeUnit.MILLISECONDS, new Flags.Create().setNoDefer())
        latch
          .waitFor(DEFAULT_TIMEOUT) {
            api.close()
            intercept[ClosedFileEventMonitorException](api.stopStream(handle))
          }
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
