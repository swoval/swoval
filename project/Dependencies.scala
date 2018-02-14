package com.swoval
import sbt._

object Dependencies {
  lazy val apfs = "com.swoval" %% "file-utilities" % "1.2.2-SNAPSHOT"
  lazy val ammonite = "com.lihaoyi" %% "ammonite" % "1.0.3" % "test" cross CrossVersion.full
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  lazy val slf4j = "org.slf4j" % "slf4j-api" % "1.7.25"
  lazy val sclip = "org.nbrahms" %% "sclip" % "0.2.3-SNAPSHOT"
  lazy val utest = "com.lihaoyi" %% "utest" % "0.6.0"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3"
  lazy val scalaLangVersion = "2.12.4"
  lazy val scalaReflect = "org.scala-lang" % "scala-reflect" % scalaLangVersion
  lazy val zinc = "org.scala-sbt" %% "zinc" % "1.0.3"
  lazy val revolver = "io.spray" % "sbt-revolver" % "0.9.0"
}
