package com.swoval.files

import java.nio.file.{ Paths, Path => JPath }

object Path {
  def apply(parts: String*): JPath = {
    require(parts.nonEmpty, "You must provided at least one part of a path name")
    Paths.get(parts.head match { case "" => "/"; case h => h }, parts.tail: _*)
  }
  def separator: String = java.io.File.separator
}
