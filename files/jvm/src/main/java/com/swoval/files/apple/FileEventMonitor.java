package com.swoval.files.apple;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public interface FileEventMonitor extends AutoCloseable {

  /**
   * Start monitoring a path. This may be a no-op if a parent of the path has previously been
   * registered.
   *
   * @param path the path to monitor. The apple file events api only supports recursive monitoring,
   *     so if this path is a directory, all of its children will also be monitored.
   * @param latency the minimum latency with which the os will deliver file events. It seems to be
   *     limited to roughly O(10ms), so there is little point in setting the value less than that.
   * @param timeUnit the unit in which the latency is specified
   * @param flags the flags specified to create the stream
   * @return an opaque handle to the native stream object
   * @throws ClosedFileEventMonitorException if the {@link FileEventMonitor} has been closed
   */
  FileEventMonitors.Handle createStream(
      Path path, long latency, TimeUnit timeUnit, Flags.Create flags)
      throws ClosedFileEventMonitorException;

  /**
   * Stop monitoring a path previously registered with {@link FileEventMonitor#createStream}.
   *
   * @param streamHandle the handle returned by {@link FileEventMonitor#createStream}
   * @throws ClosedFileEventMonitorException if the {@link FileEventMonitor} has been closed
   */
  void stopStream(FileEventMonitors.Handle streamHandle) throws ClosedFileEventMonitorException;

  /** Handle all exceptions. */
  @Override
  void close();
}
