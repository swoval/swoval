package com.swoval.app

import scala.concurrent.duration._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait Shutdownable {
  def shutdown(): Unit
  def waitForShutdown(): Unit = waitForShutdown(Duration.Inf)
  def waitForShutdown(timeout: Duration): Boolean = true
}
