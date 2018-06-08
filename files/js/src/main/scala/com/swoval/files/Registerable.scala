package com.swoval.files

import java.io.IOException
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey

/**
 * Augments the [[https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html java.nio.file.WatchService]]
 * with a [[Registerable.register]] method. This is because [[https://docs.oracle.com/javase/7/docs/api/java/nio/file/Path.html.register]]
 * does not work on custom watch services.
 */
trait Registerable extends java.nio.file.WatchService {

  /**
   * Register a path for monitoring.
   *
   * @param path The path to monitor.
   * @param kinds The types of events to monitor.
   * @return
   */
  def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey

}
