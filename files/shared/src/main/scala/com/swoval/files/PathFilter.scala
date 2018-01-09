package com.swoval.files

import java.io.{ File, FileFilter }

trait PathFilter extends Function[Path, Boolean]
object PathFilter {
  implicit class FromFileFilter(val f: FileFilter) extends PathFilter {
    def apply(p: Path): Boolean = f.accept(new File(p.fullName))
  }
}
