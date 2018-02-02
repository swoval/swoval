package com.swoval.files

import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }

import com.swoval.concurrent.ThreadFactory

class ExecutorServiceWrapper(val s: ExecutorService) extends Executor {
  override def run(runnable: Runnable): Unit = s.submit(runnable)
  override def run[R](f: => R): Unit = s.submit((() => f): Runnable)
  override def close(): Unit = {
    if (!s.isShutdown) {
      s.shutdownNow()
      try {
        s.awaitTermination(5, TimeUnit.SECONDS)
      } catch { case _: InterruptedException => }
    }
  }
}

object ExecutorServiceWrapper {
  def make(poolName: String) =
    new ExecutorServiceWrapper(Executors.newSingleThreadExecutor(new ThreadFactory(poolName)))
}
