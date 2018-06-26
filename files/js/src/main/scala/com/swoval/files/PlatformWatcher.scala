package com.swoval.files

import com.swoval.files.DirectoryWatcher.Event
import com.swoval.functional.Consumer
object PlatformWatcher {
  def make(callback: Consumer[Event],
           registerable: Registerable,
           callbackExecutor: Executor,
           internalExecutor: Executor,
           options: DirectoryWatcher.Option*): DirectoryWatcher = {
    new NioDirectoryWatcherImpl(callback, callbackExecutor, internalExecutor, options: _*)
  }
  def make(callback: Consumer[Event],
           registerable: Registerable,
           internalExecutor: Executor,
           options: DirectoryWatcher.Option*): DirectoryWatcher = {
    make(callback, registerable, Executor.make("callback"), internalExecutor, options: _*)
  }
  def make(callback: Consumer[Event],
           executor: Executor,
           options: DirectoryWatcher.Option*): DirectoryWatcher = {
    make(callback, null, executor, options: _*)
  }
}
