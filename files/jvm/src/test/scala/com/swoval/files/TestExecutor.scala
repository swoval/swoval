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
  private[this] val lock = new Object

  def clear(): Unit = {
    lock.synchronized(running = false)
  }

  def overflow(): Unit = {
    lock.synchronized(running = true)
  }

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  override def run(runnable: Runnable): Unit = lock.synchronized {
    if (running) {
      throw new RejectedExecutionException()
    }
    try {
      executor.submit(new Runnable {
        override def run(): Unit = runnable.run()
      })
    } catch {
      case _: RejectedExecutionException =>
    }
  }

  override def close(): Unit = {
    if (!executor.isShutdown) executor.shutdownNow()
    executor.awaitTermination(5, TimeUnit.SECONDS)
    super.close()
  }

  override def toString = s"TestExecutor($name, running = $running)"
}
