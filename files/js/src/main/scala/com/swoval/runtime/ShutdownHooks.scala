package com.swoval.runtime

object ShutdownHooks {
  def addHook(priority: Int, runnable: Runnable): Unit = ()
}
