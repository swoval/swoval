package com.swoval.watchservice

import sbt._
import io.WatchService
import Keys._

import scala.util.Properties

object MacOSXWatchServicePlugin extends AutoPlugin {
  private def createWatchService(): WatchService =
    if (Properties.isMac) MacOSXWatchService else Watched.createWatchService()
  override lazy val buildSettings =
    super.buildSettings :+ (watchService := { () => createWatchService() })
}
