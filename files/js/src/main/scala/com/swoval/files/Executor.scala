package com.swoval.files

import com.swoval.functional.{ Consumer, Either }

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with the jvm
 * interoperability.
 */
private[files] abstract class Executor extends AutoCloseable {
  private[this] var _closed = false

  def copy(): Executor = this

  def delegate[T](consumer: Consumer[T]): Consumer[T] = consumer

  def getThread(): Executor.Thread

  def run(consumer: Consumer[Executor.Thread], priority: Int): Unit = {
    try {
      consumer.accept(getThread())
    } catch {
      case e: Exception =>
        System.err.println(s"Error running: $consumer\n$e\n${e.getStackTrace mkString "\n"}")
    }
  }
  def run(consumer: Consumer[Executor.Thread]): Unit = run(consumer, -1)
  def block(consumer: Consumer[Executor.Thread]): Unit = consumer.accept(getThread())
  def block[T](function: Function[Executor.Thread, T]): Either[Exception, T] =
    try {
      Either.right(function.apply(getThread()))
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
  trait Thread

  /**
   * Make a new instance of an Executor
   *
   * @param name Unused but exists for jvm source compatibility
   * @return
   */
  def make(name: String): Executor = new Executor {
    override def getThread(): Thread = new Thread {}
    override def run(consumer: Consumer[Executor.Thread]): Unit = consumer.accept(getThread())
  }
}
