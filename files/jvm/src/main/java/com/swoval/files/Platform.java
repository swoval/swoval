package com.swoval.files;

public class Platform {
  static boolean isMac() {
    return System.getProperty("os.name", "").startsWith("Mac OS X");
  }

  static boolean isWin() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  static String tmpDir() {
    return System.getProperty("java.io.tmpdir", "/tmp");
  }
}
