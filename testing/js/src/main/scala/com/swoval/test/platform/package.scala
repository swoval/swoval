package com.swoval.test

import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.path.Path.sep

package object platform {
  def createTempFile(dir: String, prefix: String): String = {
    val path = s"$dir$sep$prefix${new scala.util.Random().alphanumeric.take(10).mkString}"
    Fs.closeSync(Fs.openSync(path, "w"))
    path
  }

  def createTempDirectory(): String = Fs.mkdtempSync("/tmp/")

  def createTempSubdirectory(dir: String): String = Fs.mkdtempSync(s"$dir$sep")

  def delete(dir: String): Unit = {
    if (Fs.existsSync(dir)) {
      if (Fs.statSync(dir).isDirectory()) {
        Fs.readdirSync(dir).view map (p => s"$dir$sep$p") foreach { p =>
          if (Fs.statSync(p).isDirectory) delete(p) else Fs.unlinkSync(p)
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
