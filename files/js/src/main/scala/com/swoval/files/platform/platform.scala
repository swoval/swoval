package com.swoval.files

import io.scalajs.nodejs.fs.Fs

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.scalajs.js.timers._
import scala.util.Try

package object platform {
  type Consumer[T] = Function[T, Unit]
  private[this] object executor extends ScheduledExecutor {
    override def run(runnable: Runnable): Unit = runnable.run()
    override def run[R](f: => R): Unit = f
    override def close(): Unit = {}
    override def toExecutionContext: ExecutionContext = ExecutionContext.global
    override def schedule[R](delay: FiniteDuration)(f: => R): Future[R] = {
      val p = Promise[R]
      val now = System.currentTimeMillis
      setTimeout(delay)(p.tryComplete(Try(f)))
      p.future
    }
  }
  def makeExecutor(name: String): Executor = executor
  def makeScheduledExecutor(name: String): ScheduledExecutor = executor
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
