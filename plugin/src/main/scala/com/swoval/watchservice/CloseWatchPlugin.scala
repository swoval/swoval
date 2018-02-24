package com.swoval.watchservice

import java.io.FileFilter

import com.swoval.files.{ FileCache, Path }
import sbt.Keys._
import sbt.internal.io.Source
import sbt.io.WatchService
import sbt.{ FileFilter => _, _ }

import scala.concurrent.duration._
import scala.util.Properties

object CloseWatchPlugin extends AutoPlugin {
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
    lazy val sourceDiff = taskKey[Unit]("Use default sbt include filters.")
  }
  import autoImport._

  private implicit def toSwovalPath(f: File): Path = Path(f.toString)
  private def toFile(s: Path): File = new File(s.fullName)
  private def defaultSourcesFor(conf: Configuration) = Def.task[Seq[File]] {
    def list(p: File) =
      fileCache.value
        .list(Path(p.toPath.toString), recursive = false, _ => false)
        .view
        .map(toFile)
    // This has the side effect of registering these directories with the watch service.
    (unmanagedSourceDirectories in conf).value foreach list
    (managedSourceDirectories in conf).value foreach list
    Classpaths.concat(unmanagedSources in conf, managedSources in conf).value.distinct.toIndexedSeq
  }
  private def cachedSourcesFor(conf: Configuration, sourcesInBase: Boolean) = Def.task[Seq[File]] {
    def filter(in: FileFilter, ex: FileFilter) = sbtFilter(f => in.accept(f) && !ex.accept(f))
    def list(recursive: Boolean, filter: FileFilter) =
      (f: File) => fileCache.value.list(f, recursive = recursive, filter)

    val unmanagedDirs = (unmanagedSourceDirectories in conf).value.distinct
    val unmanagedIncludeFilter = ((includeFilter in unmanagedSources) in conf).value
    val unmanagedExcludeFilter = ((excludeFilter in unmanagedSources) in conf).value
    val unmanagedFilter = filter(unmanagedIncludeFilter, unmanagedExcludeFilter)

    val baseDirs = if (sourcesInBase) Seq((baseDirectory in conf).value) else Seq.empty
    val baseFilter = filter(unmanagedIncludeFilter, unmanagedExcludeFilter)

    val unmanaged = unmanagedDirs flatMap list(recursive = true, unmanagedFilter)
    val base = baseDirs.flatMap(d => list(recursive = false, baseFilter && nodeFilter(d))(d))
    ((unmanaged ++ base).view.map(toFile) ++ (managedSources in conf).value).distinct.toIndexedSeq
  }

  private def sourcesFor(conf: Configuration) = Def.taskDyn[Seq[File]] {
    if (useDefaultSourceList.value) defaultSourcesFor(conf)
    else cachedSourcesFor(conf, sourcesInBase.value)
  }
  private def sbtFilter(f: FileFilter) = new SimpleFileFilter(f.accept)
  private def nodeFilter(dir: File) = new SimpleFileFilter(f => f.toPath.getParent == dir.toPath)
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
    watchSources := Def.taskDyn {
      val baseDir = baseDirectory.value
      val include =
        if (useDefaultWatchSourceList.value) (includeFilter in unmanagedSources).value
        else (includeFilter in unmanagedSources).value && nodeFilter(baseDir)
      val exclude = (excludeFilter in unmanagedSources).value
      val baseSources =
        if (sourcesInBase.value)
          Seq(new Source(baseDir, include, exclude, recursive = false))
        else Nil
      val unmanagedSourceDirs = ((unmanagedSourceDirectories in Compile).value ++
        (unmanagedSourceDirectories in Test).value).map(_.toPath)
      val managed = ((managedSources in Compile).value ++ (managedSources in Test).value)
        .filter(d => unmanagedSourceDirs.exists(p => d.toPath startsWith p))
        .toSet
      val managedFilter: Option[sbt.io.FileFilter] =
        if (managed.isEmpty) None else Some(new SimpleFileFilter(managed.contains))
      Def.task[Seq[WatchSource]] {
        getSources(unmanagedSourceDirectories, unmanagedSources, managedFilter).value ++
          getSources(unmanagedResourceDirectories, unmanagedResources, managedFilter).value ++
          baseSources
      }
    }.value,
    sourceDiff := {
      val ref = thisProjectRef.value.project
      val default = (defaultSourcesFor(Compile).value ++ defaultSourcesFor(Test).value).toSet
      val cached =
        (cachedSourcesFor(Compile, true).value ++ cachedSourcesFor(Test, true).value).toSet
      val (cachedExtra, defaultExtra) = (cached diff default, default diff cached)
      def msg(version: String, paths: Set[File]) =
        s"The $version source files in $ref had the following extra paths:\n${paths mkString "\n"}"
      if (cachedExtra.nonEmpty) println(msg("cached", cachedExtra))
      if (defaultExtra.nonEmpty) println(msg("default", defaultExtra))
      if (cachedExtra.isEmpty && defaultExtra.isEmpty)
        println(s"No difference in $ref between sbt default source files and from the cache.")
    },
    fileCache := FileCache.default,
  )
  private def getSources(key: SettingKey[Seq[File]],
                         scope: TaskKey[Seq[File]],
                         extraExclude: Option[FileFilter] = None) =
    Def.task {
      val dirs = (key in Compile).value ++ (key in Test).value
      val ef = (excludeFilter in scope).value
      val include = (includeFilter in scope).value
      val exclude = extraExclude match {
        case Some(e) => new SimpleFileFilter(p => ef.accept(p) || e.accept(p))
        case None    => (excludeFilter in scope).value
      }
      dirs.map(b => new Source(b, include, exclude))
    }
  override lazy val globalSettings = super.globalSettings ++ Seq(
    useDefaultWatchService := false,
    useDefaultSourceList := false,
    useDefaultIncludeFilters := false,
    useDefaultWatchSourceList := false,
    onLoad := { state =>
      val filtered = state.definedCommands.filterNot(SimpleCommandMatcher.nameMatches("~"))
      state.copy(definedCommands = filtered :+ Continuously.continuous)
    },
    onUnload := { state =>
      val p = sbt.Project.extract(state)
      state.log.debug("Closing file cache: ${p.get(fileCache)}")
      p.get(fileCache).close()
      state
    }
  )
}
