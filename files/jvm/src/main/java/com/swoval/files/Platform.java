package com.swoval.files;

/** Provides some platform specific properties. */
public class Platform {
  private static final boolean isLinuxValue = System.getProperty("os.name", "").startsWith("Linux");
  private static final boolean isMacValue =
      System.getProperty("os.name", "").startsWith("Mac OS X");
  private static final boolean isWinValue = System.getProperty("os.name", "").startsWith("Windows");
  private static final String tmpDirValue = System.getProperty("java.io.tmpdir", "/tmp");

  public static boolean isJVM() {
    return true;
  }

  public static boolean isMac() {
    return isMacValue;
  }

  public static boolean isWin() {
    return isWinValue;
  }

  public static boolean isLinux() {
    return isLinuxValue;
  }

  public static String tmpDir() {
    return tmpDirValue;
  }
}
