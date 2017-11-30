MacOSXWatchService
===
This is an sbt plugin that replaces the native java PollingWatchService with the MacOSXWatchService, which uses the apple file system api to receive file events.

Usage
---
Add `addSbtPlugin("com.swoval" %% "sbt-mac-watch-service" % "1.1.0")` to your project/plugins.sbt.  You can tune the plugin with the following settings (default values follow the :=):

`pollInterval := 75.milliseconds` -- This overrides the internal sbt pollInterval duration. SBT currently polls the WatchService for events at this rate. Reducing the value decreases latency but increases cpu utilization.

`watchLatency := 50.milliseconds` -- Sets the latency parameter which causes the underly apple file system api to buffer events for this duration. Lower values reduces the trigger latency, but, if the values are too small, multiple builds can be triggered for the same event.

`watchQueueSize := 256` -- Sets the maximum number of cached file system events per watched path. Decrease to reduce memory utilization.

Credits
---
The initial implementation was based on [directory-watcher](https://github.com/gmethvin/directory-watcher), which in turn relied on [takari-directoy-watcher](https://github.com/takari/directory-watcher).

License
---
This library is licensed under the Apache 2 license. See LICENSE for more information.
