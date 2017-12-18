package com.swoval.watchservice

import com.swoval.watchservice.files.{ FileCache, NoCache }
import sbt.Keys._
import sbt.{ FileFilter => _, _ }
import sbt.io.WatchService
import java.io.FileFilter

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
    lazy val useDefaultIncludeFilters = settingKey[Boolean]("Use default sbt include filters.")
  }
  import autoImport._

  private[watchservice] lazy val _addedFilters =
    AttributeKey[Boolean]("_SwovalAddedFiltersInternal", "Internal flag")
  private def defaultSourcesFor(conf: Configuration) = Def.task {
    def list(p: File) = fileCache.value.list(p.toPath, recursive = false, _ => false)
    (unmanagedSourceDirectories in conf).value foreach list
    (managedSourceDirectories in conf).value foreach list
    Classpaths.concat(unmanagedSources in conf, managedSources in conf).value
  }
  private def cachedSourcesFor(conf: Configuration) = Def.task {
    def filter(in: FileFilter, ex: FileFilter): FileFilter = f => in.accept(f) && !ex.accept(f)
    def list(p: File, filter: FileFilter) = fileCache.value.list(p.toPath, recursive = true, filter)
    val unmanagedFilter =
      filter((includeFilter in unmanagedSources).value, (excludeFilter in unmanagedSources).value)
    val managedFilter =
      filter((includeFilter in managedSources).value, (excludeFilter in managedSources).value)
    ((unmanagedSourceDirectories in conf).value flatMap (list(_, unmanagedFilter))) ++
      ((managedSourceDirectories in conf).value flatMap (list(_, managedFilter)))
  }

  private def sourcesFor(conf: Configuration) = Def.taskDyn {
    if (useDefaultSourceList.value) defaultSourcesFor(conf) else cachedSourcesFor(conf)
  }
  override lazy val projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ Seq(
    pollInterval := 75.milliseconds, // sbt polls the watch service for events at this rate
    watchLatency := 50.milliseconds, // os x file system api buffers events for this duration
    watchQueueSize := 256, // maximum number of buffered events per watched path
    sources in Compile := sourcesFor(Compile).value,
    sources in Test := sourcesFor(Test).value,
    watchService := (() => createWatchService(watchLatency.value, watchQueueSize.value)),
    fileCache := FileCache.default,
  )
  override lazy val globalSettings = super.globalSettings ++ Seq(
    useDefaultWatchService := false,
    useDefaultSourceList := false,
    onLoad := { s =>
      val commands = s.definedCommands
        .filterNot(_ == BasicCommands.continuous) :+ Continuously.continuous
      val extracted = Project.extract(s)
      (extracted.getOpt[Boolean](useDefaultIncludeFilters) match {
        case Some(false) if !s.attributes.contains(_addedFilters) =>
          val filters = Seq(
            includeFilter in unmanagedSources := ExtensionFilter("scala", "java"),
            includeFilter in unmanagedJars := ExtensionFilter("jar", "so", "dll", "jnilib", "zip"),
          )
          extracted.append(filters, s.put(_addedFilters, true))
        case _ => s
      }).copy(definedCommands = commands)
    },
  )
}
