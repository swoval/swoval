package com.swoval.files

import java.util.concurrent.{
  LinkedBlockingDeque,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit
}

import com.swoval.concurrent.ThreadFactory

class TestExecutor(name: String) extends Executor {
  private[this] val executor = Executor.make(name)

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
    executor.run(runnable)
  }

  override def close(): Unit = {
    executor.close()
  }

  override def toString = s"TestExecutor($name, running = $running)"
}
