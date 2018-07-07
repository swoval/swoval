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

public class WatchServices {

  public static RegisterableWatchService get() throws IOException, InterruptedException {
    return Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchServiceImpl();
  }

  /** Wraps a WatchService and implements {@link com.swoval.files.RegisterableWatchService} */
  static class RegisterableWatchServiceImpl
      implements WatchService, com.swoval.files.RegisterableWatchService {
    private final WatchService watchService;

    public RegisterableWatchServiceImpl(final WatchService watchService) {
      this.watchService = watchService;
    }

    public RegisterableWatchServiceImpl() throws IOException {
      this(FileSystems.getDefault().newWatchService());
    }

    @Override
    public WatchKey register(final Path path, final Kind<?>... kinds) throws IOException {
      return watchService instanceof com.swoval.files.RegisterableWatchService
          ? ((com.swoval.files.RegisterableWatchService) watchService).register(path, kinds)
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
}
