package com.swoval.files

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

trait Executor extends AutoCloseable {
  def run(runnable: Runnable): Unit
  def run[R](f: => R): Unit
  def toExecutionContext: ExecutionContext
}
trait ScheduledExecutor extends Executor {
  def schedule[R](delay: FiniteDuration)(f: => R): Future[R]
}
object Executor {
  def make(name: String): Executor = platform.makeExecutor(name)
}
