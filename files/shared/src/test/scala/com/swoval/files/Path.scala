package com.swoval.files

import java.nio.file.{ Paths, Path => JPath }

object Path {
  val root = if (Platform.isWin) "C:" else ""
  def apply(parts: String*): JPath = {
    require(parts.nonEmpty, "You must provided at least one part of a path name")
    parts.head match {
      case "" => Paths.get(s"/${parts.tail.head}", parts.tail.tail: _*)
      case h  => Paths.get(h, parts.tail: _*)
    }
  }
  def separator: String = java.io.File.separator
}
