import sbt.Defaults.sbtPluginExtra

val bundlerVersion = Option(System.getProperty("bundler.version")).getOrElse("0.14.0")
val crossprojectVersion = "0.4.0"
val scalaJSVersion = Option(System.getProperty("scala.js.version")).getOrElse("0.6.26")

addSbtPlugin("com.swoval" % "sbt-source-format" % "0.3.1")

addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.1.0")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % scalaJSVersion)

addSbtPlugin("org.portable-scala" % "sbt-crossproject" % crossprojectVersion)

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % crossprojectVersion)

addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % bundlerVersion)

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0")

libraryDependencies ++= {
  val sbtV = (sbtBinaryVersion in update).value
  val scalaV = (scalaBinaryVersion in update).value
  val legacy = CrossVersion.partialVersion(sbtV).forall(_._1 == 0)
  val doge = "com.eed3si9n" % "sbt-doge" % "0.1.5"
  if (legacy) Some(sbtPluginExtra(doge, sbtV, scalaV)) else None
}
