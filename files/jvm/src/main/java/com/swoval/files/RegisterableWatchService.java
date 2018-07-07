package com.swoval.files;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;

/**
 * Augments the <a
 * href="https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html">java.nio.file.WatchService</a>
 * with a {@link RegisterableWatchService#register} method. This is because <a
 * href="https://docs.oracle.com/javase/7/docs/api/java/nio/file/Path.html#register(java.nio.file.WatchService,%20java.nio.file.WatchEvent.Kind...)">Path.register</a>
 * does not work on custom watch services.
 */
public interface RegisterableWatchService extends java.nio.file.WatchService {

  /**
   * Register a path for monitoring.
   *
   * @param path The path to monitor.
   * @param kinds The types of events to monitor.
   * @return WatchKey the key returned by the WatchService
   * @throws IOException when the path cannot be registered
   */
  WatchKey register(final Path path, final WatchEvent.Kind<?>... kinds) throws IOException;
}
