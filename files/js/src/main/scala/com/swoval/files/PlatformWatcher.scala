package com.swoval.files

import com.swoval.files.PathWatchers.Event
import com.swoval.functional.Consumer

object PlatformWatcher {
  def make(callback: Consumer[Event],
           registerable: Registerable,
           callbackExecutor: Executor,
           internalExecutor: Executor,
           directoryRegistry: DirectoryRegistry,
           options: PathWatchers.Option*): PathWatcher = {
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
           options: PathWatchers.Option*): PathWatcher = {
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
           options: PathWatchers.Option*): PathWatcher = {
    make(callback, null, executor, directoryRegistry, options: _*)
  }
}
