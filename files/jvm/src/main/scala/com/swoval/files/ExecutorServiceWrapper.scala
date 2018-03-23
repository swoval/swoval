package com.swoval.files

import java.util.concurrent.{ ExecutorService, Executors, ScheduledExecutorService, TimeUnit }

import com.swoval.concurrent.ThreadFactory

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Try

class ExecutorServiceWrapper(val s: ExecutorService) extends Executor {
  override def run(runnable: Runnable): Unit = s.submit(runnable)
  override def run[R](f: => R): Unit =
    s.submit(new Runnable {
      override def run(): Unit = f
    })
  override def close(): Unit = {
    if (!s.isShutdown) {
      s.shutdownNow()
      try {
        s.awaitTermination(5, TimeUnit.SECONDS)
      } catch { case _: InterruptedException => }
    }
  }
  override def toExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(s)
}
class ScheduledExecutorServiceWrapper(override val s: ScheduledExecutorService)
    extends ExecutorServiceWrapper(s)
    with ScheduledExecutor {
  override def schedule[R](delay: FiniteDuration)(f: => R): Future[R] = {
    val p = Promise[R]
    s.schedule(new Runnable {
      override def run(): Unit = p.tryComplete(Try(f))
    }, delay.toNanos, TimeUnit.NANOSECONDS)
    p.future
  }
}

object ExecutorServiceWrapper {
  def make(poolName: String): Executor =
    new ExecutorServiceWrapper(Executors.newSingleThreadExecutor(new ThreadFactory(poolName)))
  def makeScheduled(poolName: String): ScheduledExecutor =
    new ScheduledExecutorServiceWrapper(
      Executors.newSingleThreadScheduledExecutor(new ThreadFactory(poolName)))
}
