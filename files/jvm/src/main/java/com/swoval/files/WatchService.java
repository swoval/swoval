package com.swoval.files;

import java.io.IOException;

public class WatchService {
  public static Registerable newWatchService() throws IOException, InterruptedException {
    return Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchService();
  }
}
