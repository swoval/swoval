package com.swoval.watchservice

import java.nio.file.Path

import com.swoval.files.Directory.EntryFilter

trait SourcePath {
  def base: Path
  def filter: EntryFilter[Path]
  def recursive: Boolean
  override def equals(o: Any): Boolean = o match {
    case other: SourcePath => other.base == base && other.filter == filter
    case _                 => false
  }
  override lazy val hashCode: Int = (base :: filter :: Nil).hashCode()
}
