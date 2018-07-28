package com.swoval.files

import com.swoval.functional.{ Consumer, Either }

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with the jvm
 * interoperability.
 */
private[files] abstract class Executor extends AutoCloseable {
  private[this] var _closed = false
  private[this] val threadHandle: Executor.ThreadHandle = new Executor.ThreadHandle
  def getThreadHandle(): Executor.ThreadHandle = threadHandle

  def copy(): Executor = this

  def delegate[T](consumer: Consumer[T]): Consumer[T] = consumer

  def run(consumer: Consumer[Executor.ThreadHandle], priority: Int): Unit = {
    try {
      consumer.accept(getThreadHandle())
    } catch {
      case e: Exception =>
        System.err.println(s"Error running: $consumer\n$e\n${e.getStackTrace mkString "\n"}")
    }
  }
  def run(consumer: Consumer[Executor.ThreadHandle]): Unit = run(consumer, -1)

  /**
   * Is this executor available to invoke callbacks?
   *
   * @return true if the executor is not closed
   */
  def isClosed(): Boolean = _closed

  override def close(): Unit = _closed = true
}

object Executor {
  final class ThreadHandle {
    def release(): Unit = {}
  }

  /**
   * Make a new instance of an Executor
   *
   * @param name Unused but exists for jvm source compatibility
   * @return
   */
  def make(name: String): Executor = new Executor {
    override def run(consumer: Consumer[Executor.ThreadHandle]): Unit =
      consumer.accept(getThreadHandle)
  }
}
