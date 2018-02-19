package com.swoval

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.toPlatformDepsGroupID
import sbt.Keys._
import sbt._

object Dependencies {
  val appleEventsVersion = "1.2.0"
  val sbtIO = "org.scala-sbt" %% "io" % "1.0.1"
  val scalagen = "com.mysema.scalagen" %% "scalagen" % "0.4.0"
  val scalaMacros = "org.scala-lang" % "scala-reflect"
  val utestVersion = "0.6.0"
  val utest = "com.lihaoyi" %% "utest" % utestVersion % "test"

  def baseVersion: String = "1.2.0"
  def ioScalaJS: SettingsDefinition = libraryDependencies += "io.scalajs" %%% "nodejs" % "0.4.2"
  def utestCrossMain = libraryDependencies += "com.lihaoyi" %%% "utest" % utestVersion
  def utestCrossTest = libraryDependencies += "com.lihaoyi" %%% "utest" % utestVersion % "test"
  def utestFramework = testFrameworks += new TestFramework("utest.runner.Framework")
}
