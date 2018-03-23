package com.swoval.watchservice

import com.swoval.files.{ Path, PathFilter }

trait SourcePath {
  def base: Path
  def filter: PathFilter
  private[this] case class Hash(b: Path, f: PathFilter)
  override def equals(o: Any): Boolean = o match {
    case other: SourcePath => other.base == base && other.filter == filter
    case _                 => false
  }
  protected def hash(filter: PathFilter): Int = Hash(base, filter).hashCode()
  override lazy val hashCode: Int = hash(filter)
}
