package com.swoval.files

import java.io.{ File, FileFilter }

import com.swoval.files.PathFilter.CombinedFilter

trait PathFilter extends Function[Path, Boolean] {
  def &&(other: PathFilter) = CombinedFilter(this, other)
}
object PathFilter {
  case class CombinedFilter(left: PathFilter, right: PathFilter) extends PathFilter {
    override def apply(path: Path): Boolean = left(path) && right(path)
  }
  implicit class FromFileFilter(val f: FileFilter) extends PathFilter {
    def apply(p: Path): Boolean = f.accept(new File(p.fullName))
    override def equals(o: Any): Boolean = o match {
      case that: FromFileFilter => this.f == that.f
      case _                    => false
    }
  }
  implicit class FromFunction(val f: Function[Path, Boolean]) extends PathFilter {
    def apply(p: Path): Boolean = f.apply(p)
    override def equals(o: Any): Boolean = o match {
      case that: FromFunction => this.f == that.f
      case _                  => false
    }
  }
}
