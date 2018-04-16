package com.swoval.files

case class FileWatchEvent[+P <: Path](path: P, kind: FileWatchEvent.Kind)

object FileWatchEvent {
  object Ignore extends (FileWatchEvent[_] => Unit) {
    override def apply(fileWatchEvent: FileWatchEvent[_]): Unit = {}
  }
  sealed trait Kind
  case object Create extends Kind
  case object Delete extends Kind
  case object Modify extends Kind
}
