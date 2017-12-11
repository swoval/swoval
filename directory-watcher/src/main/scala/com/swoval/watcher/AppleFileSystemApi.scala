package com.swoval.watcher

import ch.jodersky.jni.nativeLoader

@nativeLoader("sbt-apple-file-system0")
object AppleFileSystemApi {
  @native def close(handle: Long): Unit
  @native def init(callback: DirectoryWatcher.Callback): Long
  @native def loop(): Unit
  @native def createStream(path: String, latency: Double, flags: Int, handle: Long): Long
  @native def stopStream(handle: Long, streamHandle: Long): Unit
}
