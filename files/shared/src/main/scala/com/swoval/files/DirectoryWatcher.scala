package com.swoval.files

object DirectoryWatcher {
  type Callback = FileWatchEvent => Unit
}

trait DirectoryWatcher extends AutoCloseable {
  def onFileEvent: DirectoryWatcher.Callback
  def register(path: Path): Boolean
  def unregister(path: Path): Unit
}
