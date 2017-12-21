package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback

class NioDirectoryWatcher(override val onFileEvent: Callback) extends DirectoryWatcher {
  override def register(path: Path): Boolean = ???

  override def unregister(path: Path): Unit = ???

  override def close(): Unit = ???
}
