package com.swoval.files

trait Executor extends AutoCloseable {
  def run(runnable: Runnable): Unit
  def run[R](f: => R): Unit
}
object Executor {
  def make(name: String): Executor = platform.makeExecutor(name)
}
