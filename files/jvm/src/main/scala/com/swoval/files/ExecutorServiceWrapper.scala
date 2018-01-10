package com.swoval.files

import java.util.concurrent.{ ExecutorService, Executors, ThreadFactory, TimeUnit }

class ExecutorServiceWrapper(val s: ExecutorService) extends Executor {
  override def run(runnable: Runnable): Unit = s.submit(runnable)
  override def run[R](f: => R): Unit = s.submit((() => f): Runnable)
  override def close(): Unit = {
    if (!s.isShutdown) {
      s.shutdownNow()
      s.awaitTermination(5, TimeUnit.SECONDS)
    }
  }
}

object ExecutorServiceWrapper {
  private[this] class Factory(name: String) extends ThreadFactory {
    private[this] val group = Option(System.getSecurityManager)
      .map(_.getThreadGroup)
      .getOrElse(Thread.currentThread.getThreadGroup)
    override def newThread(r: Runnable) = new Thread(group, r, name)
  }

  def make(poolName: String) =
    new ExecutorServiceWrapper(Executors.newSingleThreadExecutor(new Factory(poolName)))
}
