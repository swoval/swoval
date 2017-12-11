import Dependencies._

def baseVersion: String = "1.1.4"

def utestSettings = Seq(
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies += utest,
)

lazy val root = project
  .in(file("."))
  .aggregate(plugin, testing)

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(GitVersioning)
  .settings(
    git.baseVersion := baseVersion,
    bintrayPackage := "sbt-mac-watch-service",
    utestSettings,
    name := "sbt-mac-watch-service",
    description := "MacOSXWatchServicePlugin provides a WatchService that replaces " +
      "the default PollingWatchService on Mac OSX.",
    sbtPlugin := true,
    publishMavenStyle := false,
    scalaVersion := "2.12.4",
    organization := "com.swoval",
    libraryDependencies ++= Seq(jna, sbtIO, utest),
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    bintrayRepository := "sbt-plugins",
    bintrayOrganization := Some("swoval"),
  )
  .dependsOn(testing % "test->test")

lazy val testing = project
  .in(file("testing"))
