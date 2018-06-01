package com.swoval.test

import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.path.Path.sep

import scala.concurrent.ExecutionContext
import scala.scalajs.js.timers._

package object platform {
  object executionContext extends ExecutionContext {
    override def execute(runnable: Runnable): Unit = setTimeout(0) {
      runnable.run()
    }
    override def reportFailure(cause: Throwable): Unit =
      Console.err.println(s"Caught error running runnable $cause")
  }
  def createTempFile(dir: String, prefix: String): String = {
    val path = s"$dir$sep$prefix${new scala.util.Random().alphanumeric.take(10).mkString}"
    Fs.closeSync(Fs.openSync(path, "w"))
    path
  }

  def createTempDirectory(): String = Fs.realpathSync(Fs.mkdtempSync("/tmp/"))

  def createTempSubdirectory(dir: String): String = Fs.realpathSync(Fs.mkdtempSync(s"$dir$sep"))

  def delete(dir: String): Unit = {
    if (Fs.existsSync(dir)) {
      if (Fs.statSync(dir).isDirectory()) {
        Fs.readdirSync(dir).view map (p => s"$dir$sep$p") foreach { p =>
          try {
            val realPath = Fs.realpathSync(p)
            if (Fs.statSync(realPath).isDirectory) delete(p)
          } catch { case e: Exception => } finally Fs.unlinkSync(p)
        }
        Fs.rmdirSync(dir)
      } else {
        Fs.unlinkSync(dir)
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
