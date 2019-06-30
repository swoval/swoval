package com.swoval.runtime;

/** Provides some platform specific properties. */
public class Platform {
  private Platform() {}

  private static final boolean isFreeBSD = System.getProperty("os.name", "").startsWith("FreeBSD");
  private static final boolean isLinuxValue = System.getProperty("os.name", "").startsWith("Linux");
  private static final boolean isMacValue =
      System.getProperty("os.name", "").startsWith("Mac OS X");
  private static final boolean isWinValue = System.getProperty("os.name", "").startsWith("Windows");
  private static final String tmpDirValue = System.getProperty("java.io.tmpdir", "/tmp");

  /**
   * Returns true if the runtime is a java virtual machine.
   *
   * @return true if the runtime is a java virtual machine.
   */
  public static boolean isJVM() {
    return true;
  }

  /**
   * Returns true if running on freebsd.
   *
   * @return true if running on freebsd.
   */
  public static boolean isFreeBSD() {
    return isFreeBSD;
  }
  /**
   * Returns true if running on a mac.
   *
   * @return true if running on a mac.
   */
  public static boolean isMac() {
    return isMacValue;
  }

  /**
   * Returns true if running on windows.
   *
   * @return true if running on windows.
   */
  public static boolean isWin() {
    return isWinValue;
  }

  /**
   * Returns true if running on linux.
   *
   * @return true if running on linux.
   */
  public static boolean isLinux() {
    return isLinuxValue;
  }

  /**
   * Returns the system temporary directory location
   *
   * @return the system temporary directory location
   */
  public static String tmpDir() {
    return tmpDirValue;
  }
}
