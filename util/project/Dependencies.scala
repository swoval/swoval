package com.swoval

object Dependencies {
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  lazy val slf4j = "org.slf4j" % "slf4j-api" % "1.7.25"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3"
  lazy val scalaReflect = "org.scala-lang" % "scala-reflect" % scalaLangVersion
  lazy val scalaLangVersion = "2.12.3"
}
