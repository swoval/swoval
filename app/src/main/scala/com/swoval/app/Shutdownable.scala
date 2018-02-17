package com.swoval.app

import scala.concurrent.duration._

trait Shutdownable extends Any {
  def shutdown(): Unit
  def waitForShutdown(): Unit = waitForShutdown(Duration.Inf)
  def waitForShutdown(timeout: Duration): Boolean = true
}
