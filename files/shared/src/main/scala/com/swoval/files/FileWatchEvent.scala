package com.swoval.files

case class FileWatchEvent(path: Path, kind: FileWatchEvent.Kind)

object FileWatchEvent {
  sealed trait Kind
  case object Create extends Kind
  case object Delete extends Kind
  case object Modify extends Kind
}
