package com.swoval
package watchservice

import java.io.{ File, IOException }
import java.nio.file.{ Files, Path }

import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.TypedPath
import com.swoval.functional
import sbt.SourceWrapper._
import sbt._
import sbt.internal.BuildStructure
import sbt.internal.io.Source
import sbt.io.NothingFilter

import scala.util.Try

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
  def extraProjectSettings: Seq[Def.Setting[_]] = Nil
  def settings(s: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] = s

  case class EntryImpl(getTypedPath: TypedPath) extends Entry[Path] {
    override def getValue: functional.Either[IOException, Path] =
      functional.Either.right(getTypedPath.getPath)
    override def compareTo(other: Entry[Path]): Int =
      getTypedPath.getPath.compareTo(other.getTypedPath.getPath)
  }
  private class PathFileFilter(val pathFilter: functional.Filter[Path]) extends FileFilter {
    override def accept(file: File): Boolean = pathFilter.accept(file.toPath)
    override def equals(o: Any): Boolean = o match {
      case that: PathFileFilter => this.pathFilter == that.pathFilter
      case _                    => false
    }
    override def hashCode(): Int = pathFilter.hashCode()
    override def toString: String = pathFilter.toString
  }
  def makeSource(p: Path, f: functional.Filter[Path]): WatchSource =
    Source(p.toFile, new PathFileFilter(f), NothingFilter)
  case class SourceEntryFilter(base: Path, include: Option[FileFilter], exclude: Option[FileFilter])
      extends functional.Filter[Path] {
    override def accept(p: Path): Boolean = p.startsWith(base) && {
      val f = p.toFile
      include.fold(true)(_.accept(f)) && !exclude.fold(false)(_.accept(f))
    }
  }
  class ExactFileFilter(val p: Path) extends sbt.io.FileFilter {
    override def accept(o: File): Boolean = o == p.toFile
    override def toString = s"""ExactFileFilter("$p")"""
    override def equals(o: Any): Boolean = o match {
      case that: ExactFileFilter => this.p == that.p
      case _                     => false
    }
    override def hashCode(): Int = p.hashCode()
  }
  def sourcePath(file: WatchSource): SourcePath = {
    val isDirectory = Files.isDirectory(file.base)
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
      override val recursive: Boolean = file.exclude != BaseFilter
      override val base: Path = file.base
      override val filter: functional.Filter[Path] = SourceEntryFilter(base, include, exclude)
      override lazy val toString: String = {
        if (isDirectory) {
          (if (file.exclude == BaseFilter) {
             file.include.toString
           } else {
             s"${include.fold("AllPassFilter")(Filter.show(_, 0))}${exclude.fold("")(f =>
               s" && !${Filter.show(f, 0)}")}"
           }).replaceAll("SourceFilter", "SourcePath")
        } else {
          s"""ExactPath("${file.base}")"""
        }
      }
    }
  }
  case object BaseFilter extends sbt.io.FileFilter {
    override def accept(p: File): Boolean = false
    override def toString = "NothingFilter"
  }
  def filter(files: Seq[WatchSource], id: Filter.ID): Seq[SourcePath] = files map sourcePath
  def makeScopedSource(p: Path,
                       pathFilter: functional.Filter[Entry[Path]],
                       id: Def.ScopedKey[_]): WatchSource = {
    Source(p.toFile, new SourceFilter(p, pathFilter, id) {
      override lazy val toString: String = pathFilter.toString
    }, BaseFilter)
  }
  def reapply(newSettings: Seq[Setting[_]],
              structure: BuildStructure,
              showKey: Show[Def.ScopedKey[_]]): BuildStructure =
    Reapply(newSettings, structure, showKey)
}
