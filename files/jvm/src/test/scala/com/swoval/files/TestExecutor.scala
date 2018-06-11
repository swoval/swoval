package com.swoval.files

import java.util.concurrent.{
  LinkedBlockingDeque,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit
}

import com.swoval.concurrent.ThreadFactory

class TestExecutor(name: String) extends Executor {
  private[this] val executor = new ThreadPoolExecutor(1,
                                                      1,
                                                      5,
                                                      TimeUnit.SECONDS,
                                                      new LinkedBlockingDeque[Runnable](1),
                                                      new ThreadFactory(name))

  private[this] var running = false

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  override def run(runnable: Runnable): Unit = {
    if (this.synchronized(running)) throw new RejectedExecutionException()
    try {
      executor.submit(new Runnable {
        override def run(): Unit = {
          try runnable.run()
          finally this.synchronized(running = false)
        }
      })
      this.synchronized(running = true)
    } catch {
      case e: RejectedExecutionException => this.synchronized(running = false)
    }
  }

  override def close(): Unit = {
    if (!executor.isShutdown) executor.shutdownNow()
    executor.awaitTermination(5, TimeUnit.SECONDS)
    super.close()
  }

  override def toString = s"TestExecutor($name, running = $running)"
}
