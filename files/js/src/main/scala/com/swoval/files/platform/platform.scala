package com.swoval.files

import io.scalajs.nodejs.fs.Fs

package object platform {
  type Consumer[T] = Function[T, Unit]
  private[this] object executor extends Executor {
    override def run(runnable: Runnable): Unit = runnable.run()
    override def run[R](f: => R): Unit = f
    override def close(): Unit = {}
  }
  def makeExecutor(name: String): Executor = executor
  object pathCompanion extends PathCompanion {
    def apply(parts: String*): Path = JsPath(parts: _*)

    def createTempDirectory(): Path = Path(Fs.mkdtempSync(""))

    def createTempDirectory(dir: Path, prefix: String): Path = {
      val tmpdir = dir.mkdirs()
      Path(Fs.mkdtempSync(tmpdir.resolve(Path(prefix)).fullName))
    }

    def createTempFile(dir: Path, prefix: String): Path = {
      val tmpPath = Path(s"$prefix${new scala.util.Random().alphanumeric.take(10).mkString}")
      dir.mkdirs().resolve(tmpPath).createFile()
    }
  }
}
