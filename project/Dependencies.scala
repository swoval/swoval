import sbt._

object Dependencies {
  val sbtIO = "org.scala-sbt" %% "io" % "1.0.1"
  private val utestVersion = "0.6.0"
  val utestMain = "com.lihaoyi" %% "utest" % utestVersion
  val utest = "com.lihaoyi" %% "utest" % utestVersion % "test"
  val zinc = "org.scala-sbt" %% "zinc" % "1.0.5"
  val apfs = "com.swoval" % "apple-file-system" % "1.1.8"
}
