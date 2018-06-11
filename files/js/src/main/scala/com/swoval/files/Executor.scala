package com.swoval.files

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with the jvm
 * interoperability.
 */
abstract class Executor extends AutoCloseable {
  private[this] var _closed = false

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  def run(runnable: Runnable): Unit

  /**
   * Is this executor available to invoke callbacks?
   *
   * @return true if the executor is not closed
   */
  def isClosed(): Boolean = _closed

  override def close(): Unit = _closed = true
}

object Executor {

  /**
   * Make a new instance of an Executor
   *
   * @param name Unused but exists for jvm source compatibility
   * @return
   */
  def make(name: String): Executor = new Executor {
    override def run(runnable: Runnable): Unit = runnable.run()
  }
}
