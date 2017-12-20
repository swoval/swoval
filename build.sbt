import Dependencies._

def baseVersion: String = "1.1.7"

def commonSettings = Seq(
  git.baseVersion := baseVersion,
  organization := "com.swoval",
  bintrayOrganization := Some("swoval"),
  licenses += ("Apache-2.0", url(
    "https:www.apache.org/licenses/LICENSE-2.0.html"
  )),
)

def utestSettings = Seq(
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies += utest,
)

lazy val root = project
  .in(file("."))
  .aggregate(watcher, plugin)
  .settings(
    publish := {},
    bintrayUnpublish := {},
  )

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
    libraryDependencies ++= Seq(zinc, sbtIO),
  )
  .dependsOn(watcher, testing % "test->test")

lazy val watcher = project
  .in(file("directory-watcher"))
  .enablePlugins(GitVersioning)
  .settings(
    commonSettings,
    utestSettings,
    name := "directory-watcher",
    bintrayPackage := "directory-watcher",
    bintrayRepository := "sbt-plugins",
    description := "Reactive directory watcher for OSX",
    publishMavenStyle := false,
    libraryDependencies ++= Seq(zinc, apfs)
  )
  .dependsOn(testing % "test->test")

lazy val appleFileSystem = project
  .in(file("apple-file-system"))
  .enablePlugins(JniNative)
  .settings(
    commonSettings,
    utestSettings,
    name := "apple-file-system",
    bintrayPackage := "apple-file-system",
    bintrayRepository := "sbt-plugins",
    description := "JNI library for apple file system",
    sourceDirectory in nativeCompile := sourceDirectory.value / "main" / "native",
    publishMavenStyle := false,
    target in javah := sourceDirectory.value / "main" / "native" / "include",
    watchSources ++= (sourceDirectory.value / "main" / "native" ** "*.c").get,
  )
  .dependsOn(testing % "test->test")

lazy val testing = project
  .in(file("testing"))
  .settings(
    libraryDependencies ++= Seq(utestMain),
  )
