import Dependencies._

def baseVersion: String = "1.1.4"

def commonSettings = Seq(
  git.baseVersion := baseVersion,
  organization := "com.swoval",
  bintrayOrganization := Some("swoval"),
  scalaVersion := "2.12.4",
  licenses += ("Apache-2.0", url(
    "https://www.apache.org/licenses/LICENSE-2.0.html"
  )),
  libraryDependencies += zinc
)

def utestSettings = Seq(
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies += utest,
)

lazy val root = project
  .in(file("."))
  .aggregate(testing, plugin, watcher)

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(GitVersioning)
  .settings(
    commonSettings,
    utestSettings,
    name := "sbt-mac-watch-service",
    bintrayPackage := "sbt-mac-watch-service",
    bintrayRepository := "sbt-plugins",
    description := "MacOSXWatchServicePlugin provides a WatchService that replaces " +
      "the default PollingWatchService on Mac OSX.",
    publishMavenStyle := false,
    sbtPlugin := true,
    libraryDependencies ++= Seq(sbtIO),
  )
  .dependsOn(watcher, testing % "test->test")

lazy val watcher = project
  .in(file("directory-watcher"))
  .enablePlugins(GitVersioning, JniNative)
  .settings(
    commonSettings,
    utestSettings,
    name := "directory-watcher",
    sourceDirectory in nativeCompile := sourceDirectory.value / "main" / "native",
    target in javah := sourceDirectory.value / "main" / "native" / "include",
    watchSources ++= (sourceDirectory.value / "main" / "native" ** "*.c").get,
  )
  .dependsOn(testing % "test->test")

lazy val testing = project
  .in(file("testing"))
