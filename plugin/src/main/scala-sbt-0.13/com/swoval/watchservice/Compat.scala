package com.swoval
package watchservice

import java.io.{File, IOException}
import java.nio.file._

import com.swoval.files.FileTreeDataViews.{Converter, Entry}
import com.swoval.files.{FileTreeRepositories, TypedPath}
import com.swoval.watchservice.CloseWatchPlugin.autoImport.closeWatchFileCache
import sbt.Keys._
import scala.util.Try

class FileSource(file: File, f: Filter) extends File(file.toString) with SourcePath {
  private val _f = f
  override val base: Path = file.toPath
  override val filter: functional.Filter[Path] = new functional.Filter[Path] {
    override def accept(path: Path): Boolean = f.accept(path) && path.startsWith(base)
  }
  override def recursive: Boolean = true
  override lazy val toString: String = f.toString.replaceAll("SourceFilter", "SourcePath")
  override def equals(o: Any): Boolean = o match {
    case that: FileSource => (this.base == that.base) && (this._f == that._f)
    case _                => false
  }
  override lazy val hashCode = (f :: base :: Nil).hashCode()
}
class ExactFileFilter(val f: File, override val id: Filter.ID) extends Filter {
  override val base: Path = f.toPath
  override def accept(p: Path): Boolean = p.toFile == f
  override val toString: String = s"""ExactFileFilter("$f")"""
  override def equals(o: Any): Boolean = o match {
    case that: ExactFileFilter => this.f == that.f
    case _                     => false
  }
  override lazy val hashCode: Int = f.hashCode()
}
class ExactFileSource(val file: File, id: Filter.ID) extends FileSource(file, new ExactFileFilter(file, id)) {
  override lazy val hashCode: Int = file.hashCode
  override def equals(o: Any): Boolean = o match {
    case that: ExactFileSource => this.file == that.file
    case _                     => false
  }
  override lazy val toString: String = s"""ExactFileSource("$file")"""
}
class BaseFileSource(val file: File, filter: functional.Filter[Entry[Path]], _id: Filter.ID) extends FileSource(file, new Filter {
  override def id: Filter.ID = _id
  override def base: Path = file.toPath
  override def accept(t: Path): Boolean = filter.accept(Compat.EntryImpl(t))
}) {
  override lazy val hashCode: Int = file.hashCode
  override def recursive = false
  override def equals(o: Any): Boolean = o match {
    case that: BaseFileSource => this.file == that.file
    case _                    => false
  }
}

object Compat {
  case class EntryImpl(getPath: Path) extends Entry[Path] {
    override def getValue: functional.Either[IOException, Path] = functional.Either.right(getPath)
    override def isDirectory: Boolean = Files.isDirectory(getPath)
    override def isFile: Boolean = Files.isRegularFile(getPath)
    override def isSymbolicLink: Boolean = Files.isSymbolicLink(getPath)
    override def exists(): Boolean = Files.exists(getPath)
    override def toRealPath: Path = Try(getPath.toRealPath()).getOrElse(getPath)
    override def compareTo(o: TypedPath): Int = getPath.compareTo(o.getPath)
  }
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
  import sbt.{Path => _, _}
  type Structure = sbt.BuildStructure
  type WatchSource = File
  type FileFilter = sbt.FileFilter
  val global = Scope(Global, Global, Global, Global)
  def extraProjectSettings: Seq[Def.Setting[_]] = Seq(
    pollInterval := 75,
    closeWatchFileCache := FileTreeRepositories.get(new Converter[Path] {
      override def apply(p: TypedPath): Path = p.getPath()
    })
  )
  implicit class FileFilterOps(val filter: java.io.FileFilter) extends AnyVal {
    def &&(other: java.io.FileFilter) = new sbt.FileFilter {
      override def accept(f: File): Boolean = filter.accept(f) && other.accept(f)
    }
  }
  def makeScopedSource(p: Path,
                       pathFilter: functional.Filter[Entry[Path]],
                       id: Def.ScopedKey[_]): WatchSource = {
    new BaseFileSource(p.toFile, pathFilter, id)
  }
  def makeSource(p: Path, pathFilter: Filter): WatchSource =
    new FileSource(p.toFile, pathFilter)
  def filter(files: Seq[WatchSource], id: Filter.ID): Seq[SourcePath] = {
    val (sources, rawFiles) =
      files.foldLeft((Seq.empty[File with SourcePath], Seq.empty[WatchSource])) {
        case ((s, rf), f: File with SourcePath) => (s :+ f, rf)
        case ((s, rf), f)                       => (s, rf :+ f)
      }
    val extra = rawFiles
      .filterNot(f => sources.exists(_.filter.accept(f.toPath)))
      .map(new ExactFileSource(_, id))
    sources ++ extra
  }
  def settings(s: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    inConfig(Compile)(s) ++ inConfig(Test)(s)
  def reapply(newSettings: Seq[Setting[_]],
              structure: BuildStructure,
              showKey: Show[Def.ScopedKey[_]]): BuildStructure =
    Load.reapply(newSettings, structure)(showKey)
}
