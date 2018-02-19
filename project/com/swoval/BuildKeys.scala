package com.swoval

import sbt._

object BuildKeys {
  val java8rt = settingKey[Option[String]]("Location of rt.jar for java 8")
  val genTestResourceClasses = taskKey[Unit]("Generate test resource class files.")
}
