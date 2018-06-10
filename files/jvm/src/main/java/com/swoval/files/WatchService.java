package com.swoval.files;

import com.swoval.files.apple.MacOSXWatchService;
import java.io.IOException;

/**
 * Provides a factory method to make a default {@link Registerable}. This class exists because java
 * 7 doesn't allow methods in interfaces.
 */
public class WatchService {
  public static Registerable newWatchService() throws IOException, InterruptedException {
    return Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchService();
  }
}
