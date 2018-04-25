package com.swoval.files

import com.swoval.files.Directory.PathConverter
import com.swoval.files.Path.DelegatePath

trait CachedLastModified extends Path { self: Path =>
  override def toString: String = s"CachedLastModified(${super.toString})"
}
object CachedLastModified {
  implicit object default extends PathConverter[CachedLastModified] {
    def create(p: Path): CachedLastModified = new DelegatePath with CachedLastModified {
      override def path: Path = p
      override val lastModified: Long = super.lastModified
    }
    def resolve(left: Path, right: CachedLastModified): CachedLastModified =
      new DelegatePath with CachedLastModified {
        override def path: Path = left.resolve(right)
        override val lastModified: Long = right.lastModified
      }
    def relativize(left: Path, right: CachedLastModified): CachedLastModified =
      new DelegatePath with CachedLastModified {
        override def path: Path = left.relativize(right)
        override val lastModified: Long = right.lastModified
      }
  }
}
