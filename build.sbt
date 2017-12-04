import Dependencies._

def baseVersion: String = "1.1.2"

lazy val root = project
  .in(file("."))
  .settings(
    git.baseVersion := baseVersion,
    bintrayPackage := "sbt-mac-watch-service",
    name := "sbt-mac-watch-service",
    description := "MacOSXWatchServicePlugin provides a WatchService that replaces " +
      "the default PollingWatchService on Mac OSX.",
    sbtPlugin := true,
    publishMavenStyle := false,
    scalaVersion := "2.12.4",
    organization := "com.swoval",
    libraryDependencies ++= Seq(jna, scalaTest, sbtIO),
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    bintrayRepository := "sbt-plugins",
    bintrayOrganization := Some("swoval"),
  )
