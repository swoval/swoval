package com.swoval

import sbt._

object Dependencies {
  private val scalaVersion = "2.12.4"
  lazy val scalaReflect = "org.scala-lang" % "scala-reflect" % scalaVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.4" % "test"
  lazy val zinc = "org.scala-sbt" %% "zinc" % "1.0.3"
}
