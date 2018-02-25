package com.swoval

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.toPlatformDepsGroupID
import sbt.Keys._
import sbt._

object Dependencies {
  val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  val sbtIO = "org.scala-sbt" %% "io" % "1.0.1"
  val scalagen = "com.mysema.scalagen" %% "scalagen" % "0.4.0"
  val scalaMacros = "org.scala-lang" % "scala-reflect"
  val slf4j = "org.slf4j" % "slf4j-api" % "1.7.25"
  val utestVersion = "0.6.3"
  val utest = "com.lihaoyi" %% "utest" % utestVersion % "test"
  val zinc = "org.scala-sbt" %% "zinc" % "1.0.5" % "provided"

  def ioScalaJS: SettingsDefinition = libraryDependencies += "io.scalajs" %%% "nodejs" % "0.4.2"
  def utestCrossMain = libraryDependencies += "com.lihaoyi" %%% "utest" % utestVersion
  def utestCrossTest = libraryDependencies += "com.lihaoyi" %%% "utest" % utestVersion % "test"
  def utestFramework = testFrameworks += new TestFramework("utest.runner.Framework")
}
