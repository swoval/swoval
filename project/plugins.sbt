import sbt.Defaults.sbtPluginExtra

addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.22")

addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "0.3.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.3.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.11.0")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0")

libraryDependencies ++= {
  val sbtV = (sbtBinaryVersion in update).value
  val scalaV = (scalaBinaryVersion in update).value
  val legacy = CrossVersion.partialVersion(sbtV).forall(_._1 == 0)
  val jniDep = "ch.jodersky" % "sbt-jni" % { if (legacy) "1.2.6" else "1.3.1" }
  val scalaFmtDep =
    if (legacy) "com.lucidchart" % "sbt-scalafmt" % "1.15"
    else "com.geirsson" % "sbt-scalafmt" % "1.3.0"
//  Seq(sbtPluginExtra(jniDep, sbtV, scalaV), sbtPluginExtra(scalaFmtDep, sbtV, scalaV))
  val doge = "com.eed3si9n" % "sbt-doge" % "0.1.5"
  Seq(sbtPluginExtra(jniDep, sbtV, scalaV), sbtPluginExtra(scalaFmtDep, sbtV, scalaV)) ++
    (if (legacy) Some(sbtPluginExtra(doge, sbtV, scalaV)) else None)
}
