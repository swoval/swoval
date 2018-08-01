package com.swoval.runtime

object ShutdownHooks {
  def addHook(priority: Int, runnable: Runnable): Int = -1
  def removeHook(id: Int): Unit = {}
}
