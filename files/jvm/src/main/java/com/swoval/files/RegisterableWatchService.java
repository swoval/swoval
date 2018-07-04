package com.swoval.files;

import com.swoval.files.apple.MacOSXWatchService;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/** Wraps a WatchService and implements {@link Registerable} */
public class RegisterableWatchService implements WatchService, Registerable {
  private final WatchService watchService;

  public RegisterableWatchService(final WatchService watchService) {
    this.watchService = watchService;
  }

  public RegisterableWatchService() throws IOException {
    this(FileSystems.getDefault().newWatchService());
  }

  public static Registerable newWatchService() throws IOException, InterruptedException {
    return Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchService();
  }

  @Override
  public WatchKey register(final Path path, final Kind<?>... kinds) throws IOException {
    return watchService instanceof Registerable
        ? ((Registerable) watchService).register(path, kinds)
        : path.register(watchService, kinds);
  }

  @Override
  public void close() throws IOException {
    watchService.close();
  }

  @Override
  public WatchKey poll() {
    return watchService.poll();
  }

  @Override
  public WatchKey poll(final long timeout, final TimeUnit unit) throws InterruptedException {
    return watchService.poll(timeout, unit);
  }

  @Override
  public WatchKey take() throws InterruptedException {
    return watchService.take();
  }
}
