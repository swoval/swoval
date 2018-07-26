package com.swoval.test

import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.path.Path.sep

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.scalajs.js.timers._

package object platform {
  def sleep(duration: FiniteDuration): Unit = {}
  val shutdownHooks = new ShutdownHooks {
    override def add(thread: Thread): Unit = ()
    override def remove(thread: Thread): Unit = ()
  }
  object executionContext extends ExecutionContext {
    val callbacks: mutable.Queue[Runnable] = mutable.Queue.empty
    override def execute(runnable: Runnable): Unit = {
      callbacks.enqueue(runnable)
      setTimeout(0) {
        callbacks.dequeueAll(_ => true).foreach(_.run())
      }
    }
    override def reportFailure(cause: Throwable): Unit = {
      Console.err.println(
        s"Caught error running runnable $cause\n${cause.getStackTrace mkString "\n"}")
    }
  }
  def createTempFile(dir: String, prefix: String): String = {
    val path = s"$dir$sep$prefix${new scala.util.Random().alphanumeric.take(10).mkString}"
    Fs.closeSync(Fs.openSync(path, "w"))
    path
  }

  def createTempDirectory(): String = {
    if (!Fs.existsSync("/tmp/swoval")) util.Try(Fs.mkdirSync("/tmp/swoval"))
    Fs.realpathSync(Fs.mkdtempSync("/tmp/swoval/"))
  }

  def createTempSubdirectory(dir: String): String = Fs.realpathSync(Fs.mkdtempSync(s"$dir$sep"))

  def delete(dir: String): Unit = {
    if (Fs.existsSync(dir)) {
      if (Fs.statSync(dir).isDirectory()) {
        Fs.readdirSync(dir).view map (p => s"$dir$sep$p") foreach { p =>
          try {
            val realPath = Fs.realpathSync(p)
            if (Fs.statSync(realPath).isDirectory) delete(p)
          } catch { case e: Exception => } finally {
            util.Try(Fs.unlinkSync(p))
          }
        }
        util.Try(Fs.rmdirSync(dir))
      } else {
        util.Try(Fs.unlinkSync(dir))
      }
    }
  }
  def mkdir(path: String): String = {
    if (!Fs.existsSync(path)) {
      Fs.mkdirSync(path)
    }
    path
  }
}
