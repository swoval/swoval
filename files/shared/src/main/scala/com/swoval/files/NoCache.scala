package com.swoval.files

import com.swoval.files.DirectoryWatcher.Callback

class NoCache(options: Options, callback: Callback) extends FileCache {
  private[this] val executor: Executor =
    platform.makeExecutor("com.swoval.files.NoCache.executor-thread")
  private[this] val watcher = options.toWatcher(callback, executor)
  override def close(): Unit = {
    watcher.foreach(_.close())
    executor.close()
  }
  override def list(path: Path, recursive: Boolean, filter: PathFilter): Seq[Path] = {
    watcher.foreach(_.register(path))
    if (path.exists) {
      path.list(recursive, filter)
    } else {
      Seq.empty
    }
  }
}
