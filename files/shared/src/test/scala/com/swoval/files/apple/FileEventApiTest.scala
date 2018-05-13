package com.swoval.files.apple

import com.swoval.files.apple.FileEventsApi.Consumer
import com.swoval.test.Implicits.executionContext
import com.swoval.test._
import utest._

import scala.concurrent.Promise
import scala.util.Try

object FileEventApiTest extends TestSuite {

  def getFileEventsApi(onFileEvent: FileEvent => Unit, onStreamClosed: String => Unit = _ => {}) =
    FileEventsApi.apply(new Consumer[FileEvent] {
      override def accept(fe: FileEvent): Unit = onFileEvent(fe)
    }, new Consumer[String] { override def accept(s: String): Unit = onStreamClosed(s) })
  val tests = testOn(MacOS) {
    'register - {
      val promise = Promise[Unit]
      val dir = platform.createTempDirectory()
      var file: String = null
      val api = getFileEventsApi(fe => {
        platform.delete(file)
        promise.tryComplete(Try(assert(fe.fileName.startsWith(dir))))
      })
      api.createStream(dir, 0.05, new Flags.Create().setNoDefer.getValue)
      file = platform.createTempFile(dir, "register-test")

      promise.future.andThen {
        case _ =>
          platform.delete(dir)
          api.close()
      }
    }
    'removeStream - {
      val promise = Promise[Unit]
      val dir = platform.createTempDirectory()
      var subdir: String = null
      val api = getFileEventsApi(_ => {}, s => {
        platform.delete(s)
        promise.tryComplete(Try(assert(s == subdir)))
      })
      subdir = platform.createTempSubdirectory(dir)
      api.createStream(subdir, 0.05, new Flags.Create().setNoDefer.getValue)
      api.createStream(dir, 0.05, new Flags.Create().setNoDefer.getValue)
      promise.future.andThen {
        case _ =>
          platform.delete(dir)
          api.close()
      }
    }
    'close - {
      val api = getFileEventsApi(_ => {})
      api.close()
      api.close()
    }
  }
}
