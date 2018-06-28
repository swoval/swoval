package com.swoval.files

import com.swoval.concurrent.ThreadFactory
import java.util.concurrent.{ LinkedBlockingQueue, RejectedExecutionException, ThreadPoolExecutor, TimeUnit }

class TestExecutor(name: String) extends Executor {
  private[this] var running = false
  private[this] val lock = new Object
  private[this] val factory = new ThreadFactory(name);
  private[this] val service =
    new ThreadPoolExecutor(
      1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable](), factory) {
        override def submit(runnable: Runnable) = {
          if (lock.synchronized(running)) {
            throw new RejectedExecutionException
          }
          super.submit(runnable)
        }
      };
  private[this] val executor = new Executor.ExecutorImpl(factory, service)


  def clear(): Unit = {
    lock.synchronized { running = false }
  }

  def overflow(): Unit = {
    lock.synchronized(running = true)
  }

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  override def run(runnable: Runnable): Unit = executor.run(runnable)

  override def close(): Unit = {
    executor.close()
  }

  override def toString = s"TestExecutor($name, running = $running)"
}
