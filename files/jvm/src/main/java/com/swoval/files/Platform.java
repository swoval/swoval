package com.swoval.files;

/** Provides some platform specific properties. */
public class Platform {
  private static final boolean isLinuxValue = System.getProperty("os.name", "").startsWith("Linux");
  private static final boolean isMacValue =
      System.getProperty("os.name", "").startsWith("Mac OS X");
  private static final boolean isWinValue = System.getProperty("os.name", "").startsWith("Windows");
  private static final String tmpDirValue = System.getProperty("java.io.tmpdir", "/tmp");

  /**
   *
   * @return true if the runtime is a java virtual machine
   */
  public static boolean isJVM() {
    return true;
  }

  /**
   *
   * @return true if running on a mac
   */
  public static boolean isMac() {
    return isMacValue;
  }

  /**
   * @return true if running on windows
   */
  public static boolean isWin() {
    return isWinValue;
  }

  /**
   * @return true if running on linux
   */
  public static boolean isLinux() {
    return isLinuxValue;
  }

  /**
   *
   * @return the system temporary directory location
   */
  public static String tmpDir() {
    return tmpDirValue;
  }
}
