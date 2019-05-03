package com.swoval
package watchservice

import java.io.File
import java.nio.file.Path

import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.TypedPaths
import com.swoval.watchservice.Compat.EntryImpl
import com.swoval.watchservice.Compat.io._
import com.swoval.watchservice.Filter._

object Filter {
  type ID = sbt.Def.ScopedKey[_]

  def show(f: Any, indent: Int = 0): String =
    (" " * indent) + (f match {
      case AllPassFilter       => "AllPassFilter"
      case DirectoryFilter     => "DirectoryFilter"
      case ExistsFileFilter    => "ExistsFileFilter"
      case HiddenFileFilter    => "HiddenFileFilter"
      case NothingFilter       => "NothingFilter"
      case f: SimpleFilter     => s"SimpleFilter(${f.acceptFunction})"
      case f: SimpleFileFilter => s"SimpleFileFilter(${f.acceptFunction})"
      case _                   => f.toString
    })
}

trait Filter extends functional.Filter[Path] {
  def id: ID
  def base: Path
  override def equals(o: Any): Boolean = o match {
    case that: Filter =>
      this.base == that.base && this.id == that.id
    case _ => false
  }

  override lazy val hashCode: Int = (base :: id :: Nil).hashCode()
}

class SourceFilter(override val base: Path,
                   filter: functional.Filter[Entry[Path]],
                   override val id: ID)
    extends Filter
    with Compat.FileFilter {
  override def accept(path: Path): Boolean = apply(EntryImpl(TypedPaths.get(path)))
  override def accept(file: File): Boolean = apply(EntryImpl(TypedPaths.get(file.toPath)))
  def apply(p: Entry[Path]): Boolean = p.getTypedPath.getPath.startsWith(base) && filter.accept(p)
  override lazy val toString: String = {
    val filterStr = Filter.show(filter, 0) match {
      case f if f.length > 80 =>
        f.linesIterator
          .map("    " + _)
          .map {
            case l if l.length > 80 => l.split("&&").map(_.trim).mkString("    ", " &&\n      ", "")
            case l                  => l
          }
          .mkString("\n", "\n", "")
      case f => s" $f"
    }
    s"""SourceFilter(\n  base = "$base"""" + s",\n  filter =$filterStr\n)"
  }

}
