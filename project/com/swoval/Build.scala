package com.swoval

import java.io.{ ByteArrayInputStream, File, InputStream, SequenceInputStream }
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{ Files, StandardCopyOption, Path => JPath }
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

import _root_.bintray.BintrayPlugin
import bintray.BintrayKeys._
import com.swoval.Dependencies.{ logback => SLogback, _ }
import com.typesafe.sbt.GitVersioning
import com.typesafe.sbt.SbtGit.git
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import org.apache.commons.codec.digest.DigestUtils
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.sbtplugin.JSPlatform
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.{ fastOptJS, fullOptJS, scalaJSModuleKind }
import sbt.Keys._
import sbt._
import sbtcrossproject.CrossPlugin.autoImport._
import sbtcrossproject.CrossProject
import sbtdoge.CrossPerProjectPlugin
import scalajsbundler.BundlingMode
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin.autoImport._
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.JSCrossProjectOps

import scala.collection.JavaConverters._
import scala.io.Source
import scala.tools.nsc
import scala.tools.nsc.reporters.StoreReporter
import scala.util.Properties

object Build {
  val scalaCrossVersions @ Seq(scala210, scala211, scala212) = Seq("2.10.7", "2.11.12", "2.12.4")
  val disableBintray = sys.props
    .get("SonatypeSnapshot")
    .orElse(sys.props.get("SonatypeRelease"))
    .fold(false)(_ == "true")
  def baseVersion: String = "1.3.2"
  def settings(args: Def.Setting[_]*): SettingsDefinition =
    Def.SettingsDefinition.wrapSettingsDefinition(args)
  def commonSettings: SettingsDefinition =
    settings(
      git.baseVersion := baseVersion,
      organization := "com.swoval",
      bintrayOrganization := Some("eatkins"),
      bintrayRepository := "swoval",
      homepage := Some(url("https://github.com/swoval/swoval")),
      scmInfo := Some(
        ScmInfo(url("https://github.com/swoval/swoval"), "git@github.com:swoval/swoval.git")),
      developers := List(
        Developer("username",
                  "Ethan Atkins",
                  "ethan.atkins@gmail.com",
                  url("https://github.com/eatkins"))),
      licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
      publishMavenStyle in publishLocal := false,
      publishTo := {
        val p = publishTo.value
        if (sys.props.get("SonatypeSnapshot").fold(false)(_ == "true"))
          Some(Opts.resolver.sonatypeSnapshots): Option[Resolver]
        else if (sys.props.get("SonatypeRelease").fold(false)(_ == "true"))
          Some(Opts.resolver.sonatypeReleases): Option[Resolver]
        else p
      },
      version := {
        val v = version.value
        if (sys.props.get("SonatypeSnapshot").fold(false)(_ == "true")) {
          if (v.endsWith("-SNAPSHOT")) v else s"$v-SNAPSHOT"
        } else {
          v
        }
      },
      BuildKeys.java8rt in ThisBuild := {
        if (Properties.isMac) {
          import scala.sys.process._
          Seq("mdfind", "-name", "rt.jar").!! match {
            case null => None
            case res =>
              res.split("\n").find { n =>
                !n.endsWith("alt-rt.jar") && {
                  val version =
                    Option(new JarFile(n).getManifest)
                      .map(_.getMainAttributes.getValue("Specification-Version"))
                  version.getOrElse("0").split("\\.").last == "8"
                }
              }
          }
        } else {
          None
        }
      },
      release := {},
      releaseSigned := {},
      releaseLocal := {}
    ) ++ (if (Properties.isMac) Nil else settings(publish := {}, publishSigned := {}))
  lazy val release = taskKey[Unit]("Release a project snapshot.")
  lazy val releaseLocal = taskKey[Unit]("Release local project")
  lazy val releaseSigned = taskKey[Unit]("Release signed project")
  lazy val generateJSSources = taskKey[Unit]("Generate scala sources from java")
  def projects: Seq[ProjectReference] = Seq[ProjectReference](
    appleFileEvents.jvm,
    appleFileEvents.js,
    files.js,
    files.jvm,
    plugin,
    reflect,
    testing.js,
    testing.jvm,
    util
  )

  def releaseTask(key: TaskKey[Unit]) = Def.taskDyn {
    (key in files.jvm).value
    (key in testing.jvm).value
    (key in util).value
    (scalaVersion in crossVersion).value match {
      case `scala210` => Def.task((key in plugin).value)
      case v =>
        Def.taskDyn {
          (key in appleFileEvents.js).value
          (key in reflect).value
          (key in files.js).value
          (key in testing.js).value
          if (v == scala212)
            Def.task { (key in plugin).value; (key in appleFileEvents.jvm).value } else Def.task(())
        }
    }
  }
  lazy val root = project
    .in(file("."))
    .enablePlugins(CrossPerProjectPlugin)
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .aggregate(projects: _*)
    .settings(
      crossScalaVersions := scalaCrossVersions,
      bintrayUnpublish := {},
      publishSigned := {},
      publish := {},
      publishLocal := {},
      releaseLocal := releaseTask(publishLocal).value,
      releaseSigned := releaseTask(publishSigned).value,
      release := releaseTask(publish).value
    )

  private var swovalNodeMD5Sum = ""
  lazy val appleFileEvents: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("apple-file-events"))
    .configurePlatform(JSPlatform)(_.enablePlugins(ScalaJSBundlerPlugin))
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .settings(
      commonSettings,
      name := "apple-file-events",
      bintrayPackage := "apple-file-events",
      description := "JNI library for apple file system",
      watchSources in Compile ++= {
        Files
          .walk(baseDirectory.value.toPath.getParent)
          .iterator
          .asScala
          .filter(Files.isRegularFile(_))
          .collect {
            case p if p.toString.endsWith(".hpp") || p.toString.endsWith(".cc") => p.toFile
          }
          .filterNot(_.toString contains "target")
          .toSeq
      },
      javacOptions ++= Seq("-source", "1.7", "-target", "1.7") ++
        BuildKeys.java8rt.value.map(rt => Seq("-bootclasspath", rt)).getOrElse(Seq.empty),
      javacOptions in (Compile, doc) := Seq.empty,
      utestCrossTest,
      utestFramework
    )
    .jvmSettings(
      crossPaths := false,
      autoScalaLibrary := false,
      compile in Compile := {
        val res = (compile in Compile).value
        val log = state.value.log
        if (Properties.isMac) {
          val nativeDir = sourceDirectory.value.toPath.resolve("main/native").toFile
          val proc = new ProcessBuilder("make").directory(nativeDir).start()
          proc.waitFor(1, TimeUnit.MINUTES)
          assert(proc.exitValue() == 0)
          log.info(Source.fromInputStream(proc.getInputStream).mkString)
        }
        res
      }
    )
    .jsSettings(
      crossScalaVersions := scalaCrossVersions,
      webpackBundlingMode := BundlingMode.LibraryOnly(),
      useYarn := false,
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      (compile in Compile) := {
        val npm = baseDirectory.value.toPath.resolve("npm")
        def is(path: JPath) =
          if (Files.exists(path)) Files.newInputStream(path)
          else new ByteArrayInputStream(Array.empty[Byte])
        def append(l: InputStream, r: InputStream*): InputStream = r match {
          case Seq(h)            => new SequenceInputStream(l, h)
          case Seq(h, rest @ _*) => append(new SequenceInputStream(l, h), rest: _*)
        }
        def digest: String = {
          val inputStream = append(
            is(npm.resolve("src/swoval_apple_file_system.hpp")),
            is(npm.resolve("src/swoval_apple_file_system_api_node.cc")),
            is(npm.resolve("lib/swoval_apple_file_system.node"))
          )
          try DigestUtils.md5Hex(inputStream)
          finally inputStream.close()
        }
        if (digest != swovalNodeMD5Sum) {
          val proc =
            new java.lang.ProcessBuilder("node", "install.js").directory(npm.toFile).start()
          proc.waitFor(5, TimeUnit.SECONDS)
          println(Source.fromInputStream(proc.getInputStream).mkString(""))
        }
        swovalNodeMD5Sum = digest
        (compile in Compile).value
      },
      generateJSSources := Def.task {
        val pkg = "com/swoval/files/apple"
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
        val target = (scalaSource in Compile).value.toPath.resolve(pkg)

        val cmd = Seq("java", "-classpath", cp, clazz) ++ javaSources :+ target.toString
        import scala.sys.process._
        println(cmd.!!)
      }.value,
      cleanAllGlobals,
      nodeNativeLibs
    )
    .dependsOn(testing % "test->test")

  def addLib(dir: File): File = {
    val target = dir.toPath.resolve("node_modules/lib")
    if (!Files.isSymbolicLink(target))
      Files.createSymbolicLink(target,
                               appleFileEvents.js.base.toPath.toAbsolutePath.resolve("npm/lib"))
    dir
  }
  def nodeNativeLibs: SettingsDefinition = settings(
    (npmUpdate in Compile) := addLib((npmUpdate in Compile).value),
    (npmUpdate in Test) := addLib((npmUpdate in Test).value)
  )

  def cleanGlobals(file: Attributed[File]) = {
    val content = new String(Files.readAllBytes(file.data.toPath))
      .replaceAll("([ ])*[a-zA-Z$0-9.]+\\.___global.", "$1")
    Files.write(file.data.toPath, content.getBytes)
    file
  }
  def cleanAllGlobals: SettingsDefinition = settings(
    (fastOptJS in Compile) := cleanGlobals((fastOptJS in Compile).value),
    (fastOptJS in Test) := cleanGlobals((fastOptJS in Test).value),
    (fullOptJS in Compile) := cleanGlobals((fullOptJS in Compile).value),
    (fullOptJS in Test) := cleanGlobals((fullOptJS in Test).value)
  )
  lazy val files: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("files"))
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .enablePlugins(GitVersioning)
    .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin))
    .settings(
      commonSettings,
      name := "file-utilities",
      bintrayPackage := "file-utilities",
      description := "File system apis.",
      libraryDependencies += scalaMacros % scalaVersion.value,
      utestCrossTest,
      utestFramework
    )
    .jsSettings(
      crossScalaVersions := scalaCrossVersions.drop(1),
      scalacOptions += "-P:scalajs:sjsDefinedByDefault",
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      webpackBundlingMode := BundlingMode.LibraryOnly(),
      useYarn := false,
      cleanAllGlobals,
      nodeNativeLibs,
      (fullOptJS in Compile) := {
        val files = Seq(
          "CMakeLists.txt",
          "install.js",
          "src/swoval_apple_file_system.hpp",
          "src/swoval_apple_file_system_api_node.cc"
        )
        val npm = baseDirectory.value.toPath.resolve("npm")
        Files.createDirectories(npm.resolve("src"))
        val apfs = appleFileEvents.js.base.toPath.resolve("npm")
        files.foreach(f => Files.copy(apfs.resolve(f), npm.resolve(f), REPLACE_EXISTING))

        val filesJS = (fullOptJS in Compile).value
        Files.copy(filesJS.data.toPath, npm.resolve("files.js"), REPLACE_EXISTING)
        filesJS
      },
      ioScalaJS
    )
    .jvmSettings(
      crossScalaVersions := scalaCrossVersions
    )
    .dependsOn(appleFileEvents, testing % "test->test")

  lazy val plugin: Project = project
    .in(file("plugin"))
    .enablePlugins(GitVersioning)
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .settings(
      commonSettings,
      sbtVersion in pluginCrossBuild := {
        if ((scalaVersion in crossVersion).value == scala210) "0.13.17" else "1.1.1"
      },
      crossSbtVersions := Seq("1.1.1", "0.13.17"),
      crossScalaVersions := Seq(scala210, scala212),
      name := "sbt-close-watch",
      bintrayPackage := "sbt-close-watch",
      description := "CloseWatch reduces the latency between file system events and sbt task " +
        "and command processing, especially on OSX.",
      sbtPlugin := true,
      libraryDependencies ++= Seq(
        "com.lihaoyi" %% "utest" % utestVersion % "test"
      ) ++ (if ((scalaVersion in crossVersion).value == scala210) None else Some(sbtIO)),
      publishMavenStyle in publishLocal := false,
      watchSources ++= (watchSources in files.jvm).value,
      utestFramework
    )
    .dependsOn(files.jvm % "compile->compile;test->test", testing.jvm % "test->test")

  lazy val reflect = project
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .settings(
      commonSettings,
      (scalacOptions in Compile) ++= {
        if (scalaVersion.value == scala211) Seq("-Xexperimental") else Nil
      },
      crossScalaVersions := scalaCrossVersions.drop(1),
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
          val g = nsc.Global(settings, new StoreReporter)
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
        utest
      )
    )

  lazy val scalagen: Project = project
    .in(file("scalagen"))
    .settings(
      publish := {},
      resolvers += Resolver.bintrayRepo("nightscape", "maven"),
      scalaVersion := scala211,
      crossScalaVersions := Seq(scala211),
      libraryDependencies += Dependencies.scalagen
    )

  lazy val testing: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("testing"))
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .jsSettings(
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      crossScalaVersions := scalaCrossVersions.drop(1),
      ioScalaJS
    )
    .settings(
      commonSettings,
      bintrayPackage := "testing",
      libraryDependencies += scalaMacros % scalaVersion.value,
      utestCrossMain,
      utestFramework
    )
    .jvmSettings(crossScalaVersions := scalaCrossVersions)

  lazy val util = project
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .settings(
      commonSettings,
      crossScalaVersions := scalaCrossVersions,
      bintrayPackage := "util",
      libraryDependencies ++= Seq(
        SLogback,
        slf4j
      )
    )
}
