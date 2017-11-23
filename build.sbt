import com.swoval.Dependencies._
import com.swoval.Swoval
import sbt._

lazy val printClasspath = taskKey[Unit]("Print classpath")

lazy val sharedSettings = Seq(
  scalaVersion := "2.12.4",
  organization := Swoval.organization,
  version := Swoval.version,
  libraryDependencies ++= Seq(
    scalaTest,
    zinc
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(util, reform)

lazy val util = project
  .in(file("util"))
  .settings(
    sharedSettings,
    name := "swoval-util"
  )

lazy val reform = project
  .in(file("reform"))
  .settings(
    sharedSettings,
    name := "swoval-reform",
    libraryDependencies ++= Seq(
      scalaReflect
    )
  )
