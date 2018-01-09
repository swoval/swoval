package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback

class NoCache(fileOptions: FileOptions, dirOptions: DirectoryOptions, callback: Callback)
    extends FileCache {
  private[this] val watcher = new Watcher {
    val fileMonitor = fileOptions.toWatcher(callback)
    val directoryMonitor = dirOptions.toWatcher(callback)

    override def close(): Unit = (directoryMonitor ++ fileMonitor) foreach (_.close())
    override def register(path: Path) = {
      (directoryMonitor ++ fileMonitor) foreach (_.register(path))
    }
  }
  override def close(): Unit = watcher.close()
  override def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path] = {
    watcher.register(path)
    if (path.exists) {
      path.list(recursive, filter)
    } else {
      Seq.empty
    }
  }
}
