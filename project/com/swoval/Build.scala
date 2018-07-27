package com.swoval

import java.io._
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{ Files, Paths, StandardCopyOption, Path => JPath }
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

import _root_.bintray.BintrayPlugin
import bintray.BintrayKeys._
import com.github.sbt.jacoco.JacocoKeys.{ jacocoExcludes, jacocoReportSettings }
import com.github.sbt.jacoco.report.{ JacocoReportSettings, JacocoThresholds }
import com.swoval.Dependencies.{ logback => SLogback, _ }
import com.typesafe.sbt.GitVersioning
import com.typesafe.sbt.SbtGit.git
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import org.apache.commons.codec.digest.DigestUtils
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmt
import org.scalajs.core.tools.linker.backend.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.{ fastOptJS, fullOptJS, scalaJSModuleKind }
import sbt.Keys._
import sbt._
import sbt.internal.TaskSequential
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
import scala.util.{ Properties, Try }

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
      setProp := {
        val args = Def.spaceDelimited("<arg>").parsed
        val Array(key, value) = args.toArray match {
          case Array(prop) => prop.split("=")
          case s           => s
        }
        System.setProperty(key, value)
      },
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
  lazy val generateJSSource = inputKey[Unit]("Generate scala source from java")
  lazy val generateJSSources = taskKey[Unit]("Generate scala sources from java")
  lazy val clangfmt = taskKey[Unit]("Run clang format")
  lazy val javafmt = taskKey[Unit]("Run java format")
  lazy val travisQuickListReflectionTest =
    taskKey[Unit]("Check that reflection works for quick list")
  lazy val quickListReflectionTest = inputKey[Unit]("Check that reflection works for quick list")
  lazy val allTests = inputKey[Unit]("Run all tests")
  lazy val setProp = inputKey[Unit]("Set a system property")
  lazy val checkFormat = inputKey[Boolean]("Check that the source code is formatted correctly")

  def projects: Seq[ProjectReference] = Seq[ProjectReference](
    files.js,
    files.jvm,
    nio.js,
    plugin,
    reflect,
    testing.js,
    testing.jvm,
    util
  )

  def releaseTask(key: TaskKey[Unit]) = Def.taskDyn {
    val valid = checkFormat.toTask(" silent").value
    if (valid) {
      Def.taskDyn {
        (key in testing.jvm).value
        (key in util).value
        (scalaVersion in crossVersion).value match {
          case `scala210` => Def.task((key in plugin).value)
          case v =>
            Def.taskDyn {
              (key in reflect).value
              (key in nio.js).value
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
    } else {
      throw new IllegalStateException("There are local diffs.")
    }
  }

  lazy val javafmtProj = project
    .in(file("javafmt"))
    .settings(
      libraryDependencies += "com.google.googlejavaformat" % "google-java-format" % "1.6"
    )

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
      checkFormat := {
        import sys.process._
        javafmt.value
        clangfmt.value
        (scalafmt in Compile).value
        val output = Seq("git", "status").!!
        println(output)
        val result = output.contains("working tree clean")
        if (!result && !Def.spaceDelimited("<arg>").parsed.headOption.contains("silent"))
          throw new IllegalStateException("There are local diffs")
        result
      },
      releaseLocal := releaseTask(publishLocal).value,
      releaseSigned := releaseTask(publishSigned).value,
      releaseSnapshot := releaseTask(publish).value,
      javafmt := (run in (javafmtProj, Compile)).toTask(" .").value,
      clangfmt := {
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
    if (Properties.isMac) {
      if (!Files.exists(target) && !Files.isSymbolicLink(target))
        Files.createSymbolicLink(target, files.js.base.toPath.toAbsolutePath.resolve("npm/lib"))
    }
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
      sources in conf := {
        val original = (sources in conf).value
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
                  Files.createSymbolicLink(resolved, resolved.getParent.relativize(p))
                }
                Some(root.relativize(resolved))
              } else {
                None
              }
            }
          } else None
        }
        if (!Properties.isWin) {
          this.synchronized {
            val content = new String(Files.readAllBytes(root.resolve(".gitignore")))
            val name = s"$projectName ${conf.name.toUpperCase}"
            val newGitignore = if (content.contains(name)) {
              content.replaceAll(s"(?s)(#BEGIN $name SYMLINKS)(.*)(#END $name SYMLINKS)",
                                 s"$$1\n${links.sorted.mkString("\n")}\n$$3")
            } else {
              s"$content${links.mkString(s"\n#BEGIN $name SYMLINKS\n", "\n", s"\n#END $name SYMLINKS\n")}"
            }
            Files.write(root.resolve(".gitignore"), newGitignore.getBytes)
          }
        }
        (original
          .map(_.toPath)
          .filterNot(_.startsWith(shared)) ++ links.map(_.toAbsolutePath())).distinct.map(_.toFile)
      }

    settings(createLinks(Compile), createLinks(Test))
  }

  lazy val nio: CrossProject = crossProject(JSPlatform)
    .crossType(CrossType.Pure)
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .in(file("nio-js"))
    .settings(
      commonSettings,
      ioScalaJS,
      crossScalaVersions := scalaCrossVersions.drop(1),
      scalacOptions += "-P:scalajs:sjsDefinedByDefault",
      scalaJSModuleKind := ModuleKind.CommonJSModule,
      webpackBundlingMode := BundlingMode.LibraryOnly(),
      useYarn := false
    )

  lazy val files: CrossProject = crossProject(JSPlatform, JVMPlatform)
    .in(file("files"))
    .disablePlugins((if (disableBintray) Seq(BintrayPlugin) else Nil): _*)
    .enablePlugins(GitVersioning)
    .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin).dependsOn(nio.js))
    .settings(
      commonSettings,
      name := "file-utilities",
      bintrayPackage := "file-utilities",
      description := "File system apis.",
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
          catch {
            case _: IOException => ""
          } finally inputStream.close()
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
      generateJSSource := Def.inputTaskDyn {
        val arg = Def.spaceDelimited("<arg>").parsed.head
        Files
          .walk(files.jvm.base.toPath)
          .iterator
          .asScala
          .find(_.getFileName == Paths.get(s"$arg.java")) match {
          case Some(p) =>
            val pkg = p.iterator.asScala.toIndexedSeq.drop(5).dropRight(1).mkString("/")
            val target = (scalaSource in Compile).value.toPath.resolve(pkg)
            val ts = new TaskSequential {}
            ts.sequential(
              (run in scalagen in Compile).toTask(s" ${p.toAbsolutePath} $target"),
              scalafmt in Compile,
              compile in Compile,
              Def.task(())
            )
          case _ => Def.task(println(s"Couldn't find source file for $arg"))
        }
      }.evaluated,
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
                "ApplePathWatcher",
                "BiConsumer",
                "CachedDirectory",
                "CachedDirectoryImpl",
                "CacheObservers",
                "DirectoryLister",
                "DirectoryView",
                "DirectoryRegistry",
                "Entries",
                "FileCacheDirectoryTree",
                "FileCachePathWatcher",
                "FileTreeDataView",
                "FileTreeDataViews",
                "FileTreeRepository",
                "FileTreeRepositoryImpl",
                "FileTreeRepositories",
                "FileTreeView",
                "FileTreeViews",
                "Function",
                "MapOps",
                "NioDirectoryLister",
                "NioPathWatcher",
                "Observers",
                "PathWatcher",
                "PathWatchers",
                "RegisterableWatchService",
                "SimpleFileTreeView",
                "SymlinkWatcher",
                "TotalFunction",
                "TypedPath",
                "UpdatableFileTreeDataView",
                "WatchedDirectory"
              ).value
              convertSources("com/swoval/files/apple", "Event", "FileEvent", "Flags").value
              convertSources("com/swoval/functional", "Consumer", "Either", "Filter", "Filters").value
            }
          },
          scalafmt in Compile,
          compile in Compile,
          doc in Compile
        )
        .value,
    )
    .jvmSettings(
      createCrossLinks("FILESJVM"),
      javacOptions ++= Seq("-source",
                           "1.7",
                           "-target",
                           "1.7",
                           "-h",
                           sourceDirectory.value.toPath.resolve("main/native/include").toString) ++
        BuildKeys.java8rt.value.map(rt => Seq("-bootclasspath", rt)).getOrElse(Seq.empty) ++
        Seq("-Xlint:unchecked", "-Xlint:deprecation"),
      jacocoReportSettings in Test := JacocoReportSettings()
        .withThresholds(
          JacocoThresholds(instruction = 82,
                           branch = 75,
                           line = 84,
                           clazz = 95,
                           complexity = 70,
                           method = 84)),
      jacocoExcludes in Test := Seq(
        "com.swoval.runtime.*",
        "com.swoval.files.CachedDirectories*",
        "com.swoval.files.CacheObservers*",
        "com.swoval.files.DataViews*",
        "com.swoval.files.Observers*",
        "com.swoval.files.WatchServices*",
        "com.swoval.files.WatchedDirectory*",
        "com.swoval.files.apple.Event*",
        "com.swoval.files.apple.Flag*",
        "com.swoval.files.apple.Native*"
      ) ++ (if (!Properties.isMac) Seq("*apple*", "*Apple*", "*MacOS*")
            else Nil),
      javacOptions in (Compile, doc) :=
        Seq("-overview", baseDirectory.value.toPath.resolve("overview.html").toString),
      crossScalaVersions := scalaCrossVersions,
      crossPaths := false,
      autoScalaLibrary := false,
      unmanagedResources in Compile := {
        val log = state.value.log
        if (System.getProperty("swoval.skip.native", "false") == "false") {
          val nativeDir = sourceDirectory.value.toPath.resolve("main/native").toFile
          val proc = new ProcessBuilder("make", "-j", "8").directory(nativeDir).start()
          proc.waitFor(1, TimeUnit.MINUTES)
          log.info(Source.fromInputStream(proc.getInputStream).mkString)
          if (proc.exitValue() != 0) {
            log.error(Source.fromInputStream(proc.getErrorStream).mkString)
            throw new IllegalStateException("Couldn't build native library!")
          }
        }
        (unmanagedResources in Compile).value
      },
      fork in Test := System.getProperty("swoval.fork.tests", "false") == "true",
      travisQuickListReflectionTest := {
        quickListReflectionTest
          .toTask(" com.swoval.files.NioDirectoryLister com.swoval.files.NativeDirectoryLister")
          .value
      },
      allTests := {
        val count = Def
          .spaceDelimited("<arg>")
          .parsed
          .headOption
          .flatMap(a => Try(a.toInt).toOption)
          .getOrElse(Try(System.getProperty("swoval.alltests.iterations", "1").toInt).getOrElse(1))
        val cp = (fullClasspath in Test).value
          .map(_.data)
          .filterNot(_.toString.contains("jacoco"))
          .mkString(File.pathSeparator)
        val pb = new java.lang.ProcessBuilder("java",
                                              "-classpath",
                                              cp,
                                              "-verbose:gc",
                                              "com.swoval.files.AllTests",
                                              count.toString)
        val process = pb.inheritIO().start()
        process.waitFor()
      },
      quickListReflectionTest := {
        ("" +: Def.spaceDelimited("<arg>").parsed) foreach {
          arg =>
            val cp =
              (fullClasspath in Test).value.map(_.data).filterNot(_.toString.contains("jacoco"))
            val prefix = Seq("java", "-classpath", cp.mkString(File.pathSeparator))
            val args = prefix ++ (if (arg.nonEmpty) Seq(s"-Dswoval.directory.lister=$arg") else Nil) ++
              Seq("com.swoval.files.QuickListReflectionTest",
                  if (arg.isEmpty) "com.swoval.files.NativeDirectoryLister" else arg)
            val proc = new ProcessBuilder(args: _*).start()
            proc.waitFor(5, TimeUnit.SECONDS)
            val in = Source.fromInputStream(proc.getInputStream).mkString
            if (in.nonEmpty) System.err.println(in)
            val err = Source.fromInputStream(proc.getErrorStream).mkString
            if (err.nonEmpty) System.err.println(err)
            assert(proc.exitValue() == 0)
        }
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
    .jsConfigure(_.dependsOn(nio.js))
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
