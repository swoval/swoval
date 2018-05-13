package com.swoval.files;

public class Platform {
  static boolean isMac() {
    return System.getProperty("os.name", "").startsWith("Mac OS X");
  }
}
