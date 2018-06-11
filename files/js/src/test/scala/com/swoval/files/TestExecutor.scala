package com.swoval.files

class TestExecutor(name: String) extends Executor {

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  override def run(runnable: Runnable): Unit = {
    runnable.run()
  }
}
