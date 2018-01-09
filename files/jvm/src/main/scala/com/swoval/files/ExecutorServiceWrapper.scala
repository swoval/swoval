package com.swoval.files

import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }

class ExecutorServiceWrapper(val s: ExecutorService) extends Executor {
  override def run(runnable: Runnable): Unit = s.submit(runnable)
  override def run[R](f: => R): Unit = s.submit((() => f): Runnable)
  override def close(): Unit = {
    s.shutdownNow()
    s.awaitTermination(5, TimeUnit.SECONDS)
  }
}

object ExecutorServiceWrapper {
  def make = new ExecutorServiceWrapper(Executors.newSingleThreadExecutor)
}
