Close Watch
===
This is an sbt plugin that aims to lower the latency between file system events and sbt tasks. It replaces the default watch service with a more highly optimized service that works well on all platforms

Usage
---
Add
```
addSbtPlugin("com.swoval" %% "sbt-close-watch" % "1.2.4")
```
to your project/plugins.sbt. To apply the plugin globally, add those commands to ~/.sbt/1.0/plugins/watch.sbt (creating the file if necessary).

With luck the plugin works well with default settings, but there are a number of configuration options. By default the plugin overrides a number of sbt settings and tasks and commands:
* sources in Compile
* sources in Test
* includeFilter in managedSources
* includeFilter in unmanagedSources
* ~ (aka sbt.BasicCommands.continuous)

To prevent the plugin from overriding these sbt defaults, the following flags are available:
* useDefaultWatchService -- don't override `~`
* useDefaultSourceList -- don't override `sources in *`
* useDefaultIncludeFilters -- don't override `includeFilter in *`

The new implementation of `~` relies on a cache of source files. It can be configured with the `fileCache` key. To disable caching the sources (which impacts the `sources in *` tasks)

`fileCache := com.swoval.files.FileCaches.NoCache`

You can further tweak the file cache to control the latency of the buffered file events and whether or not to monitor files directly or just the directories (see FileCache.scala).

When using the default sbt continuous execution implementation, you can tune the watch service with the following settings (default values follow the `:=`):

`pollInterval := 75.milliseconds` -- This overrides the internal sbt pollInterval duration. SBT currently polls the WatchService for events at this rate. Reducing the value decreases latency but increases cpu utilization.

`watchLatency := 50.milliseconds` -- Sets the latency parameter which causes the underly apple file system api to buffer events for this duration. Lower values reduces the trigger latency, but, if the values are too small, multiple builds can be triggered for the same event.

`watchQueueSize := 256` -- Sets the maximum number of cached file system events per watched path. Decrease to reduce memory utilization.

Credits
---
The initial implementation was based on [directory-watcher](https://github.com/gmethvin/directory-watcher), which in turn relied on [takari-directoy-watcher](https://github.com/takari/directory-watcher). Thanks to [@francisdb](https://github.com/francisdb) who provided lots of early feedback.

License
---
This library is licensed under the MIT license. See LICENSE for more information.
