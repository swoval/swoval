package com.swoval.test

object ShutdownHooks extends ShutdownHooks {
  private final val impl: ShutdownHooks = platform.shutdownHooks
  def add(thread: Thread): Unit = impl.add(thread)
  def remove(thread: Thread): Unit = impl.remove(thread)
}
trait ShutdownHooks {
  def add(thread: Thread): Unit
  def remove(thread: Thread): Unit
}
