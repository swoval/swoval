package com.swoval.files.apple

import com.swoval.files.apple.FileEventsApi.Consumer
import com.swoval.test._
import utest._
import Implicits.executionContext

import scala.concurrent.Promise
import scala.util.Success

object FileEventApiTest extends TestSuite {
  def getFileEventsApi(onFileEvent: FileEvent => Unit, onStreamClosed: String => Unit = _ => {}) =
    FileEventsApi.apply(new Consumer[FileEvent] {
      override def accept(fe: FileEvent): Unit = onFileEvent(fe)
    }, new Consumer[String] { override def accept(s: String): Unit = onStreamClosed(s) })
  val tests = Tests {
    'register - {
      val promise = Promise[FileEvent]
      usingAsync(getFileEventsApi(fe => promise.tryComplete(Success(fe)))) { api =>
        Files.withTempDirectory { dir =>
          api.createStream(dir, 0.05, new Flags.Create().setNoDefer.getValue)
          Files.withTempFile(dir) { _ =>
            promise.future.map(e => assert(e.fileName.startsWith(dir)))
          }
        }
      }
    }
    'removeStream - {
      val promise = Promise[String]
      usingAsync(getFileEventsApi(_ => {}, s => promise.tryComplete(Success(s)))) { api =>
        Files.withTempDirectory { dir =>
          Files.withTempDirectory(dir) { subdir =>
            api.createStream(subdir, 0.05, new Flags.Create().setNoDefer.getValue)
            api.createStream(dir, 0.05, new Flags.Create().setNoDefer.getValue)
            promise.future.map(s => s ==> subdir)
          }
        }
      }
    }
    'close - {
      val api = getFileEventsApi(_ => {})
      api.close()
      api.close()
    }
  }
}
