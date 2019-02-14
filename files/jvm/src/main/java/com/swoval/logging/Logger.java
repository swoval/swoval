package com.swoval.logging;

/** A simple logger. */
public interface Logger {

  /**
   * Returns the level for this Logger.
   *
   * @return the level.
   */
  Loggers.Level getLevel();

  /**
   * Print a debug message. This is for debugging implementation details and may be very noisy.
   *
   * @param message the message to print.
   */
  void verbose(String message);
  /**
   * Print a debug message.
   *
   * @param message the message to print.
   */
  void debug(String message);

  /**
   * Print an informational message.
   *
   * @param message the message to print.
   */
  void info(String message);

  /**
   * Print a warning message.
   *
   * @param message the message to print.
   */
  void warn(String message);
  /**
   * Print an error message
   *
   * @param message the message to print.
   */
  void error(String message);
}
