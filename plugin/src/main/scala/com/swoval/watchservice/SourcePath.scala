package com.swoval
package watchservice

import java.nio.file.Path

trait SourcePath {
  def base: Path
  def filter: functional.Filter[Path]
  def recursive: Boolean
  override def equals(o: Any): Boolean = o match {
    case other: SourcePath => other.base == base && other.filter == filter
    case _                 => false
  }
  override lazy val hashCode: Int = (base :: filter :: Nil).hashCode()
}
