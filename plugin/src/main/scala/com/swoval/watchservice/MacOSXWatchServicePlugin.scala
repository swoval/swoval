package com.swoval.watchservice

import java.io.FileFilter

import com.swoval.watchservice.files.FileCache
import sbt.Keys._
import sbt.internal.io.Source
import sbt.io.WatchService
import sbt.{ FileFilter => _, _ }

import scala.concurrent.duration._
import scala.util.Properties

object MacOSXWatchServicePlugin extends AutoPlugin {
  override def trigger = allRequirements
  private def createWatchService(interval: Duration, queueSize: Int): WatchService = {
    if (Properties.isMac) new MacOSXWatchService(interval, queueSize)(_ => {})
    else Watched.createWatchService()
  }
  object autoImport {
    lazy val watchLatency = settingKey[Duration]("Set watch latency for continuous builds.")
    lazy val watchQueueSize = settingKey[Int]("Set watch event queue size for each watched file.")
    lazy val fileCache = settingKey[FileCache]("Set the file cache to use.")
    lazy val useDefaultWatchService = settingKey[Boolean]("Use the built in sbt watch service.")
    lazy val useDefaultSourceList = settingKey[Boolean]("Use default sbt source list.")
    lazy val useDefaultWatchSourceList = settingKey[Boolean]("Use default sbt watch source list.")
    lazy val useDefaultIncludeFilters = settingKey[Boolean]("Use default sbt include filters.")
  }
  import autoImport._

  private def defaultSourcesFor(conf: Configuration) = Def.task {
    def list(p: File) = fileCache.value.list(p.toPath, recursive = false, _ => false)
    (unmanagedSourceDirectories in conf).value foreach list
    (managedSourceDirectories in conf).value foreach list
    Classpaths.concat(unmanagedSources in conf, managedSources in conf).value
  }
  private def cachedSourcesFor(conf: Configuration, sourcesInBase: Boolean) = Def.task {
    def filter(in: FileFilter, ex: FileFilter): FileFilter = f => { in.accept(f) && !ex.accept(f) }
    def list(recursive: Boolean, filter: FileFilter) =
      (f: File) => fileCache.value.list(f.toPath, recursive = recursive, filter)

    val unmanagedDirs = (unmanagedSourceDirectories in conf).value
    val unmanagedIncludeFilter = ((includeFilter in unmanagedSources) in conf).value
    val unmanagedExcludeFilter = ((excludeFilter in unmanagedSources) in conf).value
    val unmanagedFilter = filter(unmanagedIncludeFilter, unmanagedExcludeFilter)

    val baseDirs = if (sourcesInBase) Seq((baseDirectory in conf).value) else Seq.empty
    val baseFilter = filter(unmanagedIncludeFilter, unmanagedExcludeFilter)

    val unmanaged = unmanagedDirs flatMap list(recursive = true, unmanagedFilter)
    val base = baseDirs flatMap list(recursive = false, baseFilter)
    unmanaged ++ base ++ (managedSources in conf).value
  }

  private def sourcesFor(conf: Configuration) = Def.taskDyn {
    if (useDefaultSourceList.value) defaultSourcesFor(conf)
    else cachedSourcesFor(conf, sourcesInBase.value)
  }
  override lazy val projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ Seq(
    pollInterval := 75.milliseconds, // sbt polls the watch service for events at this rate
    watchLatency := 50.milliseconds, // os x file system api buffers events for this duration
    watchQueueSize := 256, // maximum number of buffered events per watched path
    sources in Compile := sourcesFor(Compile).value,
    sources in Test := sourcesFor(Test).value,
    watchService := (() => createWatchService(watchLatency.value, watchQueueSize.value)),
    includeFilter in unmanagedSources := {
      if (useDefaultIncludeFilters.value) ("*.java" | "*.scala") && new SimpleFileFilter(_.isFile)
      else ExtensionFilter("scala", "java") && new SimpleFileFilter(!_.isDirectory)
    },
    includeFilter in unmanagedJars := {
      if (useDefaultIncludeFilters.value) "*.jar" | "*.so" | "*.dll" | "*.jnilib" | "*.zip"
      else ExtensionFilter("jar", "so", "dll", "jnilib", "zip")
    },
    watchSources := {
      val baseDir = baseDirectory.value
      val include =
        if (useDefaultWatchSourceList.value) (includeFilter in unmanagedSources).value
        else
          (includeFilter in unmanagedSources).value && new SimpleFileFilter(
            f => f.toPath.getParent == baseDir.toPath)
      val exclude = (excludeFilter in unmanagedSources).value
      val baseSources =
        if (sourcesInBase.value)
          Seq(new Source(baseDir, include, exclude, recursive = false))
        else Nil
      getSources(unmanagedSourceDirectories, unmanagedSources).value ++
        getSources(unmanagedResourceDirectories, unmanagedResources).value ++
        baseSources
    },
    fileCache := FileCache.default,
  )
  private def getSources(key: SettingKey[Seq[File]], scope: TaskKey[Seq[File]]) = Def.task {
    val dirs = (key in Compile).value ++ (key in Test).value
    val include = (includeFilter in scope).value
    val exclude = (excludeFilter in scope).value
    dirs.map(b => new Source(b, include, exclude))
  }
  override lazy val globalSettings = super.globalSettings ++ Seq(
    useDefaultWatchService := false,
    useDefaultSourceList := false,
    useDefaultIncludeFilters := false,
    useDefaultWatchSourceList := false,
    onLoad := { state =>
      val commands = state.definedCommands
        .filterNot(_ == BasicCommands.continuous) :+ Continuously.continuous
      state.copy(definedCommands = commands)
    },
  )
}
