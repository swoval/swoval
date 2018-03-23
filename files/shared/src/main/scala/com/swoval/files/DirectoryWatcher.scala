package com.swoval.files

import com.swoval.files.apple.Flags

import scala.concurrent.duration._
import scala.util.Properties

object DirectoryWatcher {
  type Callback = FileWatchEvent => Unit
  def default(latency: FiniteDuration,
              flags: Flags.Create = new Flags.Create().setFileEvents.setNoDefer)(
      callback: DirectoryWatcher.Callback): DirectoryWatcher = {
    if (Properties.isMac) AppleDirectoryWatcher(latency, flags)(callback)
    else new NioDirectoryWatcher(callback)
  }
  def default(callback: Callback): DirectoryWatcher =
    default(10.milliseconds, new Flags.Create().setFileEvents.setNoDefer)(callback)
}

trait DirectoryWatcher extends AutoCloseable {
  def onFileEvent: DirectoryWatcher.Callback
  def register(path: Path, recursive: Boolean = true): Boolean
  def unregister(path: Path): Unit
}
