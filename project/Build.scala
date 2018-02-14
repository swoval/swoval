import java.io.File
import java.nio.file.{Files, StandardCopyOption}

import com.swoval.Dependencies.{logback => SLogback, _}
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters._
import scala.tools.nsc

object Build {
  lazy val baseVersion = "0.1.0-SNAPSHOT"

  lazy val root = (project in file(".")).aggregate(agent, util)

  lazy val genTestResourceClasses =
    taskKey[Unit]("Generate test resource class files.")

  lazy val util = project
    .settings(
      testFrameworks += new TestFramework("utest.runner.Framework"),
      inThisBuild(
        List(
          organization := "com.swoval",
          scalaVersion := scalaLangVersion,
          version := "0.1.0-SNAPSHOT"
        )),
      name := s"com.swoval.util",
      scalacOptions := Seq("-unchecked", "-deprecation", "-feature"),
      updateOptions in Global := updateOptions
        .in(Global)
        .value
        .withCachedResolution(true),
      fork in Test := true,
      javaOptions in Test ++= Seq(
        "-Djava.system.class.loader=com.swoval.reflect.CachingClassLoader",
        "-javaagent:/Users/ethanatkins/work/swoval/util/agent/target/scala-2.12/agent_2.12-0.1.0" +
          "-SNAPSHOT.jar",
        //"-verbose:class"
      ),
      genTestResourceClasses := {
        val dir = Files.createTempDirectory("util-resources")
        try {
          val resourceDir = (resourceDirectory in Test).value.toPath
          val cp = (fullClasspath in Compile).value
            .map(_.data)
            .mkString(File.pathSeparator)
          println(cp)
          (resources in Test).value collectFirst {
            case f if f.getName == "Bar.scala.template" => IO.read(f)
          } foreach {
            f =>
              Seq(6, 7) foreach {
                i =>
                  IO.write(dir.resolve("Bar.scala").toFile,
                           f.replaceAll("\\$\\$impl", s"$i"))
                  val settings = new nsc.Settings()
                  settings.bootclasspath.value = cp
                  settings.classpath.value = cp
                  settings.usejavacp.value = true
                  settings.outputDirs.add(dir.toString, dir.toString)
                  val g = nsc.Global(settings)
                  val res =
                    new g.Run()
                      .compile(List(dir.resolve("Bar.scala").toString))
                  val (src, dst) =
                    (dir.resolve("com/swoval/reflect/Bar$.class"),
                     resourceDir.resolve(s"Bar$$.class.$i"))
                  Files.copy(dir.resolve("com/swoval/reflect/Bar$.class"),
                             resourceDir.resolve(s"Bar$$.class.$i"),
                             StandardCopyOption.REPLACE_EXISTING)
                  Files.copy(dir.resolve("com/swoval/reflect/Buzz.class"),
                             resourceDir.resolve(s"Buzz.class"),
                             StandardCopyOption.REPLACE_EXISTING)
              }
          }
        } finally {
          val files = Files
            .walk(dir)
            .iterator
            .asScala
            .toIndexedSeq
            .sortBy(_.toString)
            .reverse
          files foreach (Files.deleteIfExists(_))
        }

      },
      libraryDependencies ++= Seq(
        SLogback,
        scalaReflect,
        utest % Test,
        slf4j,
        apfs,
      )
    )
    .dependsOn(agent)

  lazy val agent = (project in file("util/agent"))
    .settings(
      packageOptions in (Compile, packageBin) +=
        Package.ManifestAttributes(
          "Premain-Class" -> "com.swoval.reflect.Agent")
    )
}
