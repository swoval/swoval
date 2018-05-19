package com.swoval

import java.io._
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{ Files, StandardCopyOption, Path => JPath }
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

import _root_.bintray.BintrayPlugin
import bintray.BintrayKeys._
import com.swoval.Dependencies.{ logback => SLogback, _ }
import com.github.sbt.jacoco.JacocoKeys.jacocoExcludes
import com.typesafe.sbt.GitVersioning
import com.typesafe.sbt.SbtGit.git
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import org.apache.commons.codec.digest.DigestUtils
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmt
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.{ fastOptJS, fullOptJS, scalaJSModuleKind }
import sbt.Keys._
import sbt._
import sbtcrossproject.CrossPlugin.autoImport._
import sbtcrossproject.{ CrossProject, crossProject }
import sbtdoge.CrossPerProjectPlugin
import scalajsbundler.BundlingMode
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin.autoImport._
import scalajscrossproject.JSPlatform
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.JSCrossProjectOps

import scala.collection.JavaConverters._
import scala.io.Source
import scala.tools.nsc
import scala.tools.nsc.reporters.StoreReporter
import scala.util.Properties

object Build {
  val scalaCrossVersions @ Seq(scala210, scala211, scala212) = Seq("2.10.7", "2.11.12", "2.12.6")
  val disableBintray = sys.props
    .get("SonatypeSnapshot")
    .orElse(sys.props.get("SonatypeRelease"))
    .fold(false)(_ == "true")
  def baseVersion: String = "1.3.2"
  def settings(args: Def.Setting[_]*): SettingsDefinition =
    Def.SettingsDefinition.wrapSettingsDefinition(args)
  def commonSettings: SettingsDefinition =
    settings(
      scalaVersion in ThisBuild := scala212,
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
      scalacOptions ++= Seq("-feature"),
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
      releaseSnapshot := {},
      releaseSigned := {},
      releaseLocal := {}
    ) ++ (if (Properties.isMac) Nil else settings(publish := {}, publishSigned := {}))
  lazy val releaseSnapshot = taskKey[Unit]("Release a project snapshot.")
  lazy val releaseLocal = taskKey[Unit]("Release local project")
  lazy val releaseSigned = taskKey[Unit]("Release signed project")
  lazy val generateJSSources = taskKey[Unit]("Generate scala sources from java")
  lazy val clangFmt = taskKey[Unit]("Run clang format")
  def projects: Seq[ProjectReference] = Seq[ProjectReference](
    files.js,
    files.jvm,
    plugin,
    reflect,
    testing.js,
    testing.jvm,
    util
  )

  def releaseTask(key: TaskKey[Unit]) = Def.taskDyn {
    import sys.process._
    clangFmt.value
    if (!Seq("git", "status").!!.contains("working tree clean"))
      throw new IllegalStateException("There are local diffs")
    else {
      Def.taskDyn {
        (key in testing.jvm).value
        (key in util).value
        (scalaVersion in crossVersion).value match {
          case `scala210` => Def.task((key in plugin).value)
          case v =>
            Def.taskDyn {
              (key in reflect).value
              (key in files.js).value
              (key in testing.js).value
              if (v == scala212)
                Def.task {
                  (key in files.jvm).value
                  (key in plugin).value
                } else Def.task(())
            }
        }
      }
    }
  }
  lazy val swoval = project
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
      releaseSnapshot := releaseTask(publish).value,
      clangFmt := {
        val npm = files.js.base.toPath.toAbsolutePath.resolve("npm/src")
        val jvm = files.jvm.base.toPath.toAbsolutePath.resolve("src/main/native")
        val args = Seq(npm, jvm).flatMap { p =>
          val allFiles = Files.list(p).iterator.asScala.toSeq
          allFiles.flatMap { f =>
            f.toString.split("\\.").lastOption.flatMap {
              case "cc" | "hpp" => Some(f.toString)
              case _            => None
            }
          }
        }
        val proc = new ProcessBuilder((Seq("clang-format", "-i") ++ args): _*).start()
        val log = state.value.log
        if (!proc.waitFor(20, TimeUnit.SECONDS) || proc.exitValue != 0) {
          log.error(Source.fromInputStream(proc.getInputStream).mkString)
          log.error(Source.fromInputStream(proc.getErrorStream).mkString)
        }
      }
    )

  private var swovalNodeMD5Sum = ""

  def addLib(dir: File): File = {
    val target = dir.toPath.resolve("node_modules/lib")
    if (!Files.isSymbolicLink(target))
      Files.createSymbolicLink(target, files.js.base.toPath.toAbsolutePath.resolve("npm/lib"))
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
  def createCrossLinks(projectName: String): SettingsDefinition = {
    def createLinks(conf: Configuration): Def.Setting[Task[Seq[File]]] =
      managedSources in conf ++= {
        val base = baseDirectory.value.toPath
        val root = base.getParent.getParent
        val shared = base.getParent.resolve("shared")
        val sourceDirectories = (unmanagedSourceDirectories in conf).value.collect {
          case dir if dir.getName != "java" && dir.exists && !dir.toPath.startsWith(shared) =>
            dir.toPath
        }
        val filter = (includeFilter in unmanagedSources).value --
          (excludeFilter in unmanagedSources).value
        val links = sourceDirectories.distinct.flatMap { dir =>
          val relative = base.relativize(dir)
          val sharedBase = shared.resolve(relative)
          if (Files.exists(dir)) {
            Files.walk(dir).iterator.asScala.foreach { p =>
              if (!Files.exists(p)) Files.deleteIfExists(p)
            }
          }
          if (Files.exists(sharedBase)) {
            Files.walk(sharedBase).iterator.asScala.flatMap { p =>
              if (filter.accept(p.toFile)) {
                val relativeSource = sharedBase.relativize(p)
                val resolved = dir.resolve(relativeSource)
                if (!Files.exists(resolved.getParent)) Files.createDirectories(resolved.getParent)
                if (!Files.exists(resolved) && !Files.isSymbolicLink(resolved)) {
                  try {
                    Files.createSymbolicLink(resolved, resolved.getParent.relativize(p))
                    Some(root.relativize(resolved))
                  } catch {
                    case e: IOException if e.toString.contains("A required privilege") =>
                      Files.copy(p, resolved, REPLACE_EXISTING)
                      Some(root.relativize(resolved))
                  }
                } else {
                  None
                }
              } else {
                None
              }
            }
          } else None
        }
        this.synchronized {
          val content = new String(Files.readAllBytes(root.resolve(".gitignore")))
          val name = s"$projectName ${conf.name.toUpperCase}"
          val newGitignore = if (content.contains(name)) {
            content.replaceAll(s"(?s)(#BEGIN $name SYMLINKS)(.*)(#END $name SYMLINKS)",
                               s"$$1\n${links.mkString("\n")}\n$$3")
          } else {
            s"$content${links.mkString(s"\n#BEGIN $name SYMLINKS\n", "\n", s"\n#END $name SYMLINKS\n")}"
          }
          Files.write(root.resolve(".gitignore"), newGitignore.getBytes)
        }
        Nil
      }
    settings(createLinks(Compile), createLinks(Test))
  }
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
      sources in Compile := {
        val unfiltered = (sources in Compile).value
        val base = baseDirectory.value.toPath.getParent.resolve("shared")
        unfiltered.filterNot(_.toPath.startsWith(base))
      },
      sources in Test := {
        val unfiltered = (sources in Test).value
        val base = baseDirectory.value.toPath.getParent.resolve("shared")
        unfiltered.filterNot(_.toPath.startsWith(base))
      },
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
      utestCrossTest,
      utestFramework
    )
    .jsSettings(
      crossScalaVersions := scalaCrossVersions.drop(1),
      scalacOptions += "-P:scalajs:sjsDefinedByDefault",
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      webpackBundlingMode := BundlingMode.LibraryOnly(),
      useYarn := false,
      createCrossLinks("FILESJS"),
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

        val filesJS = (fullOptJS in Compile).value
        Files.copy(filesJS.data.toPath, npm.resolve("files.js"), REPLACE_EXISTING)
        filesJS
      },
      compile in Compile := {
        val log = state.value.log
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
          catch { case _: IOException => "" } finally inputStream.close()
        }
        if (digest != swovalNodeMD5Sum) {
          val proc =
            new java.lang.ProcessBuilder("node", "install.js").directory(npm.toFile).start()
          proc.waitFor(30, TimeUnit.SECONDS)
          if (proc.exitValue() != 0) {
            log.error(Source.fromInputStream(proc.getInputStream).mkString(""))
            log.error(Source.fromInputStream(proc.getErrorStream).mkString(""))
            swovalNodeMD5Sum = ""
            throw new IllegalStateException("Couldn't build native node library")
          } else {
            log.info(Source.fromInputStream(proc.getInputStream).mkString(""))
          }
        }
        swovalNodeMD5Sum = digest
        (compile in Compile).value
      },
      generateJSSources := Def
        .sequential(
          Def.taskDyn {
            val base = baseDirectory.value.toPath
            def convertSources(pkg: String, sources: String*) = Def.taskDyn {
              val javaSourceDir: JPath =
                base.relativize((javaSource in Compile).value.toPath.resolve(pkg))
              val javaDir: JPath = base.resolveSibling("jvm").resolve(javaSourceDir)
              val javaSources = sources.map(f => javaDir.resolve(s"$f.java").toString)
              val target = (scalaSource in Compile).value.toPath.resolve(pkg)
              Def.task {
                (run in scalagen in Compile)
                  .toTask((javaSources :+ target).mkString(" ", " ", ""))
                  .value
              }
            }
            Def.task {
              convertSources(
                "com/swoval/files",
                "AppleDirectoryWatcher",
                "Directory",
                "DirectoryWatcher",
                "EntryFilters",
                "FileCache",
                "FileOps",
                "Observers",
                "Registerable"
              ).value
              convertSources("com/swoval/files/apple", "Event", "FileEvent", "Flags").value
            }
          },
          scalafmt in Compile,
          compile in Compile,
          doc in Compile
        )
        .value,
      ioScalaJS
    )
    .jvmSettings(
      createCrossLinks("FILESJVM"),
      javacOptions ++= Seq("-source", "1.7", "-target", "1.7") ++
        BuildKeys.java8rt.value.map(rt => Seq("-bootclasspath", rt)).getOrElse(Seq.empty) ++
        Seq("-Xlint:unchecked"),
      jacocoExcludes in Test := Seq(
        "com.swoval.files.apple.Event*",
        "com.swoval.files.apple.Flag*",
        "com.swoval.files.apple.Native*"
      ),
      javacOptions in (Compile, doc) := Nil,
      crossScalaVersions := scalaCrossVersions,
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
    .dependsOn(testing % "test->test")

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

  def addParadise = {
    libraryDependencies ++= {
      if (scalaVersion.value == scala210)
        Seq(compilerPlugin(paradise cross CrossVersion.full), quasiquotes cross CrossVersion.binary)
      else Nil
    }
  }
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
      addParadise,
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
