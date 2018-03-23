package com.swoval.watchservice

import java.io.File

import com.swoval.files.{ FileCache, Path, PathFilter }
import com.swoval.watchservice.CloseWatchPlugin.autoImport.closeWatchFileCache
import sbt.Keys._

class FileSource(file: File, f: PathFilter) extends File(file.toString) with SourcePath {
  private val _f = f
  override val base: Path = Path(file.toString)
  override val filter: PathFilter = f && ((_: Path).startsWith(base))
  override lazy val toString: String = f.toString.replaceAll("SourceFilter", "SourcePath")
  override def equals(o: Any): Boolean = o match {
    case that: FileSource => (this.base == that.base) && (this._f == that._f)
    case _                => false
  }
  override lazy val hashCode = hash(f)
}
class ExactFileFilter(f: File) extends PathFilter {
  override def apply(p: Path) = new File(p.fullName) == f
  override val toString: String = s"""ExactFileFilter("$f")"""
  override def equals(o: Any): Boolean = o match {
    case that: ExactFileFilter => this.f == that.f
    case _                     => false
  }
  override def hashCode: Int = f.hashCode()
}
class ExactFileSource(val file: File) extends FileSource(file, new ExactFileFilter(file)) {
  override lazy val hashCode: Int = file.hashCode
  override def equals(o: Any): Boolean = o match {
    case that: ExactFileSource => this.file == that.file
    case _                     => false
  }
  override lazy val toString: String = s"""ExactFileSource("$file")"""
}
class BaseFileSource(val file: File, filter: PathFilter) extends FileSource(file, filter) {
  override lazy val hashCode: Int = file.hashCode
  override def equals(o: Any): Boolean = o match {
    case that: BaseFileSource => this.file == that.file
    case _                    => false
  }
}

object Compat {
  object internal {
    val Act = sbt.Act
    val Parser = sbt.complete.Parser
  }
  object io {
    val AllPassFilter = sbt.AllPassFilter
    val DirectoryFilter = sbt.DirectoryFilter
    val ExistsFileFilter = sbt.ExistsFileFilter
    val HiddenFileFilter = sbt.HiddenFileFilter
    val NothingFilter = sbt.NothingFilter
    type SimpleFilter = sbt.SimpleFilter
    type SimpleFileFilter = sbt.SimpleFileFilter
  }
  import sbt.{ Path => _, _ }
  type Structure = sbt.BuildStructure
  type WatchSource = File
  type FileFilter = sbt.FileFilter
  val global = Scope(Global, Global, Global, Global)
  def extraProjectSettings: Seq[Def.Setting[_]] = Seq(
    pollInterval := 75,
    closeWatchFileCache := FileCache.default
  )
  implicit class FileFilterOps(val filter: java.io.FileFilter) extends AnyVal {
    def &&(other: java.io.FileFilter) = new sbt.FileFilter {
      override def accept(f: File): Boolean = filter.accept(f) && other.accept(f)
    }
  }
  def makeScopedSource(p: Path, pathFilter: PathFilter, id: Def.ScopedKey[_]): WatchSource = {
    new BaseFileSource(file(p.fullName), pathFilter)
  }
  def makeSource(p: Path, pathFilter: PathFilter): WatchSource =
    new FileSource(new File(p.fullName), pathFilter)
  def filter(files: Seq[WatchSource]): Seq[SourcePath] = {
    val (sources, rawFiles) =
      files.foldLeft((Seq.empty[File with SourcePath], Seq.empty[WatchSource])) {
        case ((s, rf), f: File with SourcePath) => (s :+ f, rf)
        case ((s, rf), f)                       => (s, rf :+ f)
      }
    val extra = rawFiles
      .filterNot(f => sources.exists(_.filter(Path(f.toString))))
      .map(new ExactFileSource(_))
    sources ++ extra
  }
  def settings(s: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    inConfig(Compile)(s) ++ inConfig(Test)(s)
  def reapply(newSettings: Seq[Setting[_]],
              structure: BuildStructure,
              showKey: Show[Def.ScopedKey[_]]): BuildStructure =
    Load.reapply(newSettings, structure)(showKey)
}
