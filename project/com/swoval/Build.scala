package com.swoval

import java.io.File
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{ Files, StandardCopyOption, Path => JPath }
import java.util.jar.JarFile

import bintray.BintrayKeys.{
  bintray => bintrayScope,
  bintrayOrganization,
  bintrayPackage,
  bintrayRepository,
  bintrayUnpublish
}
import bintray.{ Bintray, BintrayPlugin }
import ch.jodersky.sbt.jni.plugins.JniJavah.autoImport.javah
import ch.jodersky.sbt.jni.plugins.JniNative
import ch.jodersky.sbt.jni.plugins.JniNative.autoImport._
import com.swoval.Dependencies.{ logback => SLogback, _ }
import com.typesafe.sbt.GitVersioning
import com.typesafe.sbt.SbtGit.git
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.sbtplugin.JSPlatform
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.{ fastOptJS, fullOptJS, scalaJSModuleKind }
import sbt.Keys._
import sbt._
import sbtcrossproject.CrossPlugin.autoImport._
import sbtcrossproject.CrossProject

import scala.collection.JavaConverters._
import scala.sys.process._
import scala.tools.nsc
import scala.util.Properties
import scalajsbundler.BundlingMode
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin.autoImport._
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.JSCrossProjectOps

object Build {
  def commonSettings: SettingsDefinition = Seq(
    scalaVersion := "2.12.4",
    resolvers += Resolver.sonatypeRepo("releases"),
    git.baseVersion := baseVersion,
    organization := "com.swoval",
    bintrayOrganization := Some("swoval"),
    bintrayRepository := "sbt-plugins",
    homepage := Some(url("https://github.com/swoval/swoval")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/swoval/swoval"), "git@github.com:swoval/swoval.git")),
    developers := List(
      Developer("username",
                "Ethan Atkins",
                "ethan.atkins@gmail.com",
                url("https://github.com/eatkins"))),
    licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
    publishMavenStyle := true,
    publishTo := Some(
      if (isSnapshot.value)
        Opts.resolver.sonatypeSnapshots
      else
        Opts.resolver.sonatypeStaging
    ),
    publishMavenStyle in bintrayScope := false,
    BuildKeys.java8rt in ThisBuild := {
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
    }
  )
  val projects: Seq[ProjectReference] =
    (if (Properties.isMac) Seq[ProjectReference](appleFileEvents.jvm, appleFileEvents.js, plugin)
     else Seq.empty) ++
      Seq[ProjectReference](
        files.js,
        files.jvm,
        reflect,
        testing.js,
        testing.jvm,
        util
      )

  lazy val root = project
    .in(file("."))
    .aggregate(projects: _*)
    .settings(
      bintrayUnpublish := {},
      publish := {},
    )

  lazy val appleFileEvents: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("apple-file-events"))
    .configurePlatform(JVMPlatform)(_.enablePlugins(JniNative))
    .configurePlatform(JSPlatform)(_.enablePlugins(ScalaJSBundlerPlugin))
    .settings(
      commonSettings,
      name := "apple-file-events",
      bintrayPackage := "apple-file-events",
      description := "JNI library for apple file system",
      sourceDirectory in nativeCompile := sourceDirectory.value / "main" / "native",
      target in javah := sourceDirectory.value / "main" / "native" / "include",
      watchSources ++= sourceDirectory.value.globRecursive("*.hpp" | "*.cc").get,
      javacOptions ++= Seq("-source", "1.8", "-target", "1.8") ++
        BuildKeys.java8rt.value.map(rt => Seq("-bootclasspath", rt)).getOrElse(Seq.empty),
      javacOptions in (Compile, doc) := Seq.empty,
      utestCrossTest,
      utestFramework
    )
    .jsSettings(
      webpackBundlingMode := BundlingMode.LibraryOnly(),
      useYarn := false,
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      sourceGenerators in Compile += Def.task {
        val pkg = "com/swoval/files/apple"
        val target = (managedSourceDirectories in Compile).value.head.toPath
        val base = baseDirectory.value.toPath
        val javaSourceDir: JPath =
          base.relativize((javaSource in Compile).value.toPath.resolve(pkg))
        val javaDir: JPath = base.resolveSibling("jvm").resolve(javaSourceDir)
        val sources = Seq("Event", "Flags", "FileEvent")
        val javaSources = sources.map(f => javaDir.resolve(s"$f.java").toString)

        val clazz = "com.swoval.code.Converter"
        def cp =
          fullClasspath
            .in(scalagen, Runtime)
            .value
            .map(_.data)
            .mkString(File.pathSeparator)
        val cmd = Seq("java", "-classpath", cp, clazz) ++ javaSources :+ target.toString
        println(cmd.!!)
        sources.map(f => target.resolve(s"$f.scala").toFile)
      }.taskValue,
      cleanAllGlobals,
      nodeNativeLibs
    )

  def addLib(dir: File): File = {
    val target = dir.toPath.resolve("node_modules/lib")
    if (!Files.exists(target))
      Files.createSymbolicLink(target,
                               appleFileEvents.js.base.toPath.toAbsolutePath.resolve("npm/lib"))
    dir
  }
  def nodeNativeLibs: SettingsDefinition = Seq(
    (npmUpdate in Compile) := addLib((npmUpdate in Compile).value),
    (npmUpdate in Test) := addLib((npmUpdate in Test).value)
  )

  def cleanGlobals(file: Attributed[File]) = {
    val content = new String(Files.readAllBytes(file.data.toPath))
      .replaceAll("([ ])*[a-zA-Z$0-9.]+\\.___global.", "$1")
    Files.write(file.data.toPath, content.getBytes)
    file
  }
  def cleanAllGlobals: SettingsDefinition = Seq(
    (fastOptJS in Compile) := cleanGlobals((fastOptJS in Compile).value),
    (fastOptJS in Test) := cleanGlobals((fastOptJS in Test).value),
    (fullOptJS in Compile) := cleanGlobals((fullOptJS in Compile).value),
    (fullOptJS in Test) := cleanGlobals((fullOptJS in Test).value)
  )
  lazy val files: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("files"))
    .enablePlugins(GitVersioning)
    .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin))
    .jsSettings(
      scalacOptions += "-P:scalajs:sjsDefinedByDefault",
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      webpackBundlingMode := BundlingMode.LibraryOnly(),
      useYarn := false,
      cleanAllGlobals,
      nodeNativeLibs,
      (fullOptJS in Compile) := {
        val res = (fullOptJS in Compile).value
        Files.copy(res.data.toPath,
                   baseDirectory.value.toPath.resolve("npm/files.js"),
                   REPLACE_EXISTING)
        res
      },
      ioScalaJS
    )
    .settings(
      scalaVersion := "2.12.4",
      commonSettings,
      name := "file-utilities",
      bintrayPackage := "file-utilities",
      description := "File system apis.",
      libraryDependencies += scalaMacros % scalaVersion.value,
      utestCrossTest,
      utestFramework
    )
    .dependsOn(appleFileEvents, testing % "test->test")

  lazy val plugin: Project = project
    .in(file("plugin"))
    .enablePlugins(GitVersioning, BintrayPlugin)
    .settings(
      commonSettings,
      name := "sbt-mac-watch-service",
      bintrayPackage := "sbt-mac-watch-service",
      description := "MacOSXWatchServicePlugin provides a WatchService that replaces " +
        "the default PollingWatchService on Mac OSX.",
      sbtPlugin := true,
      libraryDependencies ++= Seq(
        sbtIO % "provided",
        "com.lihaoyi" %% "utest" % utestVersion % "test"
      ),
      watchSources ++= (watchSources in files.jvm).value,
      utestFramework
    )
    .dependsOn(files.jvm % "compile->compile;test->test", testing.jvm % "test->test")

  lazy val reflect = project
    .settings(
      commonSettings,
      testFrameworks += new TestFramework("utest.runner.Framework"),
      scalacOptions := Seq("-unchecked", "-deprecation", "-feature"),
      updateOptions in Global := updateOptions
        .in(Global)
        .value
        .withCachedResolution(true),
      fork in Test := true,
      javacOptions ++= Seq("-source", "1.8", "-target", "1.8") ++
        BuildKeys.java8rt.value.map(rt => Seq("-bootclasspath", rt)).getOrElse(Seq.empty),
      javaOptions in Test ++= Def.taskDyn {
        val forked = (fork in Test).value
        lazy val agent = (packageConfiguration in (Compile, packageBin)).value.jar
        Def.task {
          val loader = "-Djava.system.class.loader=com.swoval.reflect.ChildFirstClassLoader"
          if (forked) Seq(loader, s"-javaagent:$agent") else Seq.empty
        }
      }.value,
      packageOptions in (Compile, packageBin) +=
        Package.ManifestAttributes("Premain-Class" -> "com.swoval.reflect.Agent"),
      BuildKeys.genTestResourceClasses := {
        val dir = Files.createTempDirectory("util-resources")
        try {
          val resourceDir = (resourceDirectory in Test).value.toPath
          val cp = (fullClasspath in Compile).value
            .map(_.data)
            .mkString(File.pathSeparator)
          val settings = new nsc.Settings()
          settings.bootclasspath.value = cp
          settings.classpath.value = cp
          settings.usejavacp.value = true
          settings.outputDirs.add(dir.toString, dir.toString)
          val g = nsc.Global(settings)
          (resources in Test).value collect {
            case f if f.getName == "Bar.scala.template"  => ("Bar", IO.read(f))
            case f if f.getName == "Buzz.scala.template" => ("Buzz", IO.read(f))
          } foreach {
            case ("Bar", f) =>
              Seq(6, 7) foreach { i =>
                IO.write(dir.resolve("Bar.scala").toFile, f.replaceAll("\\$\\$impl", s"$i"))
                new g.Run().compile(List(dir.resolve("Bar.scala").toString))
                Files.copy(dir.resolve("com/swoval/reflect/Bar$.class"),
                           resourceDir.resolve(s"Bar$$.class.$i"),
                           StandardCopyOption.REPLACE_EXISTING)
              }
            case ("Buzz", f) =>
              IO.write(dir.resolve("Buzz.scala").toFile, f)
              new g.Run().compile(List(dir.resolve("Buzz.scala").toString))
              Files.copy(dir.resolve("com/swoval/reflect/Buzz.class"),
                         resourceDir.resolve(s"Buzz.class"),
                         StandardCopyOption.REPLACE_EXISTING)
            case (_, _) =>
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
      // Qualify test with Keys to remove intellij warning
      Keys.test in Test := {
        (packageBin in Compile).value
        (Keys.test in Test).value
      },
      libraryDependencies ++= Seq(
        scalaMacros % scalaVersion.value,
        utest,
        zinc // AbortMacroException is not found without this dependency
      )
    )

  lazy val scalagen: Project = project
    .in(file("scalagen"))
    .settings(
      publish := {},
      resolvers += Resolver.bintrayRepo("nightscape", "maven"),
      scalaVersion := "2.11.12",
      libraryDependencies += Dependencies.scalagen
    )

  lazy val testing: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("testing"))
    .jsSettings(
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      ioScalaJS
    )
    .settings(
      commonSettings,
      bintrayPackage := "testing",
      libraryDependencies += scalaMacros % scalaVersion.value,
      utestCrossMain,
      utestFramework
    )

  lazy val util = project
    .settings(
      commonSettings,
      bintrayPackage := "util",
      libraryDependencies ++= Seq(
        SLogback,
        slf4j
      )
    )
}
