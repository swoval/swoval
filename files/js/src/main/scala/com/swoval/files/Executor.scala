package com.swoval.files

import java.util.concurrent.Callable
import com.swoval.functional.Either

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with the jvm
 * interoperability.
 */
abstract class Executor extends AutoCloseable {
  private[this] var _closed = false

  def copy(): Executor = this

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  def run(runnable: Runnable): Unit = {
    try {
      runnable.run()
    } catch {
      case e: Exception =>
        System.err.println(s"Error running: $runnable\n$e\n${e.getStackTrace mkString "\n"}")
    }
  }
  def block(runnable: Runnable): Unit = runnable.run()
  def block[T](callable: Callable[T]): Either[Exception, T] =
    try {
      Either.right(callable.call())
    } catch {
      case e: Exception => Either.left(e)
    }

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
