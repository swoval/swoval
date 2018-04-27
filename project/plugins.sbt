import sbt.Defaults.sbtPluginExtra

val bundlerVersion = Option(System.getProperty("bundler.version")).getOrElse("0.12.0")
val crossprojectVersion = "0.4.0"
val scalaJSVersion = Option(System.getProperty("scala.js.version")).getOrElse("0.6.22")

addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")

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
  val scalaFmtDep =
    if (legacy) "com.lucidchart" % "sbt-scalafmt" % "1.15"
    else "com.geirsson" % "sbt-scalafmt" % "1.3.0"
  val doge = "com.eed3si9n" % "sbt-doge" % "0.1.5"
  Seq(sbtPluginExtra(scalaFmtDep, sbtV, scalaV)) ++
    (if (legacy) Some(sbtPluginExtra(doge, sbtV, scalaV)) else None)
}
