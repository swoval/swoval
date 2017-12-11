import sbt._

object Dependencies {
  val jna = "net.java.dev.jna" % "jna" % "4.5.0"
  val sbtIO = "org.scala-sbt" %% "io" % "1.0.1"
  val utest = "com.lihaoyi" %% "utest" % "0.6.0" % "test"
  val zinc = "org.scala-sbt" %% "zinc" % "1.0.5"
}
