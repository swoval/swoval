import com.swoval.Dependencies._
import com.swoval.Swoval
import sbt._
import scala.concurrent.duration._

lazy val printClasspath = taskKey[Unit]("Print classpath")

lazy val sharedSettings = Seq(
  scalaVersion := "2.11.11",
  organization := Swoval.organization,
  version := Swoval.version,
  libraryDependencies ++= Seq(
    scalaTest,
    zinc
  ),
  parallelExecution := true,
  fork in Test := true
//  pollInterval := 100.milliseconds
)

lazy val root = project
  .in(file("."))
  .aggregate(reform)
  //.disablePlugins(sbt.plugins.JUnitXmlReportPlugin)

lazy val reform = project
  .in(file("reform"))
  .settings(
    sharedSettings,
    name := "reform",
    libraryDependencies ++= Seq(
      scalaReflect
    )
  )
  //.disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
