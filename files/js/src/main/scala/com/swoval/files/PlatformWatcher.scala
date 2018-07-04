package com.swoval.files

import com.swoval.files.PathWatcher.Event
import com.swoval.functional.Consumer

object PlatformWatcher {
  def make(callback: Consumer[Event],
           registerable: Registerable,
           callbackExecutor: Executor,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry,
           options: PathWatcher.Option*): PathWatcher = {
    new NioPathWatcherImpl(callback,
                           callbackExecutor,
                           internalExecutor,
                           directoryRegistry,
                           options: _*)
  }
  def make(callback: Consumer[Event],
           registerable: Registerable,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry,
           options: PathWatcher.Option*): PathWatcher = {
    make(callback,
         registerable,
         Executor.make("callback"),
         internalExecutor,
         directoryRegistry,
         options: _*)
  }
  def make(callback: Consumer[Event],
           executor: Executor,
           directoryRegistry: DirectoryRegistry,
           options: PathWatcher.Option*): PathWatcher = {
    make(callback, null, executor, directoryRegistry, options: _*)
  }
}
