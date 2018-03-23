package com.swoval.watchservice

import java.io.File

import com.swoval.files.{ Path, PathFilter }
import sbt.Keys._
import sbt.SourceWrapper._
import sbt._
import sbt.internal.BuildStructure
import sbt.internal.io.Source
import sbt.io.{ NothingFilter, WatchService }

import scala.concurrent.duration._
import scala.util.Properties

object Compat {
  object internal {
    val Act = sbt.internal.Act
    val Parser = sbt.internal.util.complete.Parser
  }
  object io {
    val AllPassFilter = sbt.io.AllPassFilter
    val DirectoryFilter = sbt.io.DirectoryFilter
    val ExistsFileFilter = sbt.io.ExistsFileFilter
    val HiddenFileFilter = sbt.io.HiddenFileFilter
    val NothingFilter = sbt.io.NothingFilter
    type SimpleFilter = sbt.io.SimpleFilter
    type SimpleFileFilter = sbt.io.SimpleFileFilter
  }
  type Structure = sbt.internal.BuildStructure
  type WatchSource = sbt.internal.io.Source
  type FileFilter = sbt.io.FileFilter
  val global = Global
  import CloseWatchPlugin.autoImport._
  private def createWatchService(interval: Duration, queueSize: Int): WatchService = {
    if (Properties.isMac) new MacOSXWatchService(interval, queueSize)(_ => {})
    else Watched.createWatchService()
  }
  def extraProjectSettings: Seq[Def.Setting[_]] =
    Seq(
      watchService := (() =>
                         createWatchService(closeWatchLegacyWatchLatency.value,
                                            closeWatchLegacyQueueSize.value)))
  def settings(s: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] = s

  private class PathFileFilter(val pathFilter: PathFilter) extends FileFilter {
    override def accept(file: File): Boolean = pathFilter.apply(Path(file.toString))
    override def equals(o: Any): Boolean = o match {
      case that: PathFileFilter => this.pathFilter == that.pathFilter
      case _                    => false
    }
    override def hashCode(): Int = pathFilter.hashCode()
    override def toString: String = pathFilter.toString
  }
  def makeSource(p: Path, f: PathFilter): WatchSource =
    Source(new File(p.fullName), new PathFileFilter(f), NothingFilter)
  case class SourcePathFilter(base: Path, include: Option[FileFilter], exclude: Option[FileFilter])
      extends PathFilter {
    override def apply(p: Path): Boolean = p.startsWith(base) && {
      val f = file(p.fullName)
      include.fold(true)(_.accept(f)) && !exclude.fold(false)(_.accept(f))
    }
  }
  class ExactFileFilter(val p: Path) extends sbt.io.FileFilter {
    override def accept(o: File): Boolean = o.toString == p.fullName
    override def toString = s"""ExactFileFilter("${p.fullName}")"""
    override def equals(o: Any): Boolean = o match {
      case that: ExactFileFilter => this.p == that.p
      case _                     => false
    }
    override lazy val hashCode = p.hashCode
  }
  def sourcePath(file: WatchSource): SourcePath = {
    val isDirectory = file.base.isDirectory
    new SourcePath {
      private[this] val exclude = file.exclude match {
        case NothingFilter => None
        case f             => Some(f)
      }
      private[this] val include = file.include match {
        case AllPassFilter if isDirectory => None
        case AllPassFilter                => Some(new ExactFileFilter(file.base))
        case f                            => Some(f)
      }
      override val base: Path = Path(file.base.fullName)
      override val filter: PathFilter = SourcePathFilter(base, include, exclude)
      override lazy val toString: String = {
        if (isDirectory) {
          (if (file.exclude == BaseFilter) {
             file.include.toString
           } else {
             s"${include.fold("AllPassFilter")(Filter.show(_, 0))}${exclude.fold("")(f =>
               s" && !${Filter.show(f, 0)}")}"
           }).replaceAll("SourceFilter", "SourcePath")
        } else {
          s"""ExactPath("${file.base.fullName}")"""
        }
      }
    }
  }
  case object BaseFilter extends sbt.io.FileFilter {
    override def accept(p: File): Boolean = false
    override def toString = "NothingFilter"
  }
  def filter(files: Seq[WatchSource]): Seq[SourcePath] = files map sourcePath
  def makeScopedSource(p: Path, pathFilter: PathFilter, id: Def.ScopedKey[_]): WatchSource = {
    Source(file(p.fullName), new SourceFilter(p, pathFilter, id) {
      override lazy val toString: String = pathFilter.toString
    }, BaseFilter)
  }
  def reapply(newSettings: Seq[Setting[_]],
              structure: BuildStructure,
              showKey: Show[Def.ScopedKey[_]]): BuildStructure =
    Reapply(newSettings, structure, showKey)
}
