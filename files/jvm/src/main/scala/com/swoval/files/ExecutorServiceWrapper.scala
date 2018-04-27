package com.swoval.files

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean

import com.swoval.concurrent.ThreadFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.{ Deadline, FiniteDuration }
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
  private[this] class job[R](f: => R, p: Promise[R], val deadline: Deadline) {
    def run(): Unit = p.tryComplete(Try(f))
  }
  private[this] val pending = new AtomicBoolean(false)
  private[this] val scheduledJobs = new LinkedBlockingDeque[job[_]]
  private[this] val runnable = new Runnable { override def run(): Unit = executeJobs() }
  private[this] def executeJobs(): Unit = pending.synchronized {
    val buffer: mutable.Buffer[job[_]] = mutable.Buffer.empty[job[_]]
    scheduledJobs.drainTo(buffer.asJava)
    val (ready, deferred) = buffer.partition(_.deadline.isOverdue)
    ready.foreach(_.run())
    if (deferred.nonEmpty) {
      deferred.foreach(scheduledJobs.putLast)
      val deadline = deferred.minBy(_.deadline).deadline
      s.schedule(runnable, (deadline - Deadline.now).toNanos, TimeUnit.NANOSECONDS)
    } else {
      pending.set(false)
    }
  }
  override def schedule[R](delay: FiniteDuration)(f: => R): Future[R] = pending.synchronized {
    val p = Promise[R]
    scheduledJobs.putLast(new job(f, p, delay.fromNow))
    if (!pending.get) {
      s.schedule(runnable, delay.toNanos, TimeUnit.NANOSECONDS)
      pending.set(true)
    }
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
