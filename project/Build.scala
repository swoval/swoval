import java.io.File
import java.nio.file.{ Files, Path, StandardCopyOption }
import java.util.jar.JarFile

import com.swoval.Dependencies.{ logback => SLogback, _ }
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters._
import scala.tools.nsc
import scala.util.Properties

object Build {
  lazy val baseVersion = "0.1.0-SNAPSHOT"

  lazy val root = (project in file(".")).aggregate(reflect, util)

  lazy val genTestResourceClasses =
    taskKey[Unit]("Generate test resource class files.")

  lazy val java8rt = settingKey[Option[String]]("Location of rt.jar for java 8")

  lazy val reflect = project
    .settings(
      testFrameworks += new TestFramework("utest.runner.Framework"),
      java8rt := {
        if (Properties.isMac) {
          import scala.sys.process._
          Seq("mdfind", "-name", "rt.jar").!!.split("\n").find { n =>
            !n.endsWith("alt-rt.jar") && {
              val version =
                new JarFile(n).getManifest.getMainAttributes.getValue("Specification-Version")
              version.split("\\.").last == "8"
            }
          }
        } else {
          None
        }
      },
      inThisBuild(
        List(
          organization := "com.swoval",
          scalaVersion := scalaLangVersion,
          version := "0.1.0-SNAPSHOT"
        )),
      scalacOptions := Seq("-unchecked", "-deprecation", "-feature"),
      updateOptions in Global := updateOptions
        .in(Global)
        .value
        .withCachedResolution(true),
      fork in Test := true,
      javacOptions ++= Seq("-source", "1.8", "-target", "1.8") ++
        java8rt.value.map(rt => Seq("-bootclasspath", rt)).getOrElse(Seq.empty),
      javaOptions in Test ++= {
        println((packageConfiguration in (Compile, packageBin)).value.jar)
        Seq(
          "-Djava.system.class.loader=com.swoval.reflect.ChildFirstClassLoader",
          s"-javaagent:${(packageConfiguration in (Compile, packageBin)).value.jar}"
          //"-verbose:class"
        )
      },
      packageOptions in (Compile, packageBin) +=
        Package.ManifestAttributes("Premain-Class" -> "com.swoval.reflect.Agent"),
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
                  IO.write(dir.resolve("Bar.scala").toFile, f.replaceAll("\\$\\$impl", s"$i"))
                  val settings = new nsc.Settings()
                  settings.bootclasspath.value = cp
                  settings.classpath.value = cp
                  settings.usejavacp.value = true
                  settings.outputDirs.add(dir.toString, dir.toString)
                  val g = nsc.Global(settings)
                  new g.Run().compile(List(dir.resolve("Bar.scala").toString))
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
      testOnly in Test := {
        (packageBin in Compile).value
        (testOnly in Test).evaluated
      },
      test in Test := {
        (packageBin in Compile).value
        (test in Test).value
      },
      libraryDependencies ++= Seq(
        scalaReflect,
        utest % Test,
        apfs,
      )
    )
  lazy val util = project
    .settings(
      libraryDependencies ++= Seq(
        SLogback,
        slf4j,
      )
    )
}
