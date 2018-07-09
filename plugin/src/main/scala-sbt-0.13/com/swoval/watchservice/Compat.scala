package com.swoval
package watchservice

import java.io.{ File, IOException }
import java.nio.file._

import com.swoval.files.Directory.{ Entry, EntryFilter }
import com.swoval.files.{ Directory, FileCaches }
import com.swoval.watchservice.CloseWatchPlugin.autoImport.closeWatchFileCache
import sbt.Keys._

class FileSource(file: File, f: EntryFilter[Path]) extends File(file.toString) with SourcePath {
  private val _f = f
  override val base: Path = file.toPath
  override val filter: EntryFilter[Path] = new EntryFilter[Path] {
    override def accept(p: Entry[_ <: Path]) = f.accept(p) && p.getPath.startsWith(base)
  }
  override def recursive: Boolean = true
  override lazy val toString: String = f.toString.replaceAll("SourceFilter", "SourcePath")
  override def equals(o: Any): Boolean = o match {
    case that: FileSource => (this.base == that.base) && (this._f == that._f)
    case _                => false
  }
  override lazy val hashCode = (f :: base :: Nil).hashCode()
}
class ExactFileFilter(val f: File) extends EntryFilter[Path] {
  override def accept(p: Entry[_ <: Path]): Boolean = p.getPath.toFile == f
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
class BaseFileSource(val file: File, filter: EntryFilter[Path]) extends FileSource(file, filter) {
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
  import sbt.{ Path => _, _ }
  type Structure = sbt.BuildStructure
  type WatchSource = File
  type FileFilter = sbt.FileFilter
  val global = Scope(Global, Global, Global, Global)
  def extraProjectSettings: Seq[Def.Setting[_]] = Seq(
    pollInterval := 75,
    closeWatchFileCache := FileCaches.get(new Directory.Converter[Path] {
      override def apply(p: Path): Path = p
    })
  )
  implicit class FileFilterOps(val filter: java.io.FileFilter) extends AnyVal {
    def &&(other: java.io.FileFilter) = new sbt.FileFilter {
      override def accept(f: File): Boolean = filter.accept(f) && other.accept(f)
    }
  }
  def makeScopedSource(p: Path,
                       pathFilter: EntryFilter[Path],
                       id: Def.ScopedKey[_]): WatchSource = {
    new BaseFileSource(p.toFile, pathFilter)
  }
  def makeSource(p: Path, pathFilter: EntryFilter[Path]): WatchSource =
    new FileSource(p.toFile, pathFilter)
  def filter(files: Seq[WatchSource]): Seq[SourcePath] = {
    val (sources, rawFiles) =
      files.foldLeft((Seq.empty[File with SourcePath], Seq.empty[WatchSource])) {
        case ((s, rf), f: File with SourcePath) => (s :+ f, rf)
        case ((s, rf), f)                       => (s, rf :+ f)
      }
    val extra = rawFiles
      .filterNot(f => sources.exists(_.filter.accept(EntryImpl(f.toPath()))))
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
