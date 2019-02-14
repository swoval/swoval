package com.swoval.logging;

import java.io.IOException;
import java.io.OutputStream;

public class Loggers {
  private static Logger global = null;
  private static final Object lock = new Object();

  public abstract static class Level implements Comparable<Level> {
    Level() {}

    public static Level fromString(final String string) {
      switch (string.toLowerCase()) {
        case "verbose":
          return VERBOSE;
        case "debug":
          return DEBUG;
        case "info":
          return INFO;
        case "warn":
          return WARN;
        default:
          return ERROR;
      }
    }

    public static final Level DEBUG =
        new Level() {
          @Override
          public int compareTo(final Level that) {
            return that == VERBOSE ? 1 : that == DEBUG ? 0 : -1;
          }

          @Override
          public String toString() {
            return "DEBUG";
          }
        };

    public static final Level INFO =
        new Level() {
          @Override
          public int compareTo(final Level that) {
            return that == INFO ? 0 : (that == DEBUG || that == VERBOSE) ? 1 : -1;
          }

          @Override
          public String toString() {
            return "INFO";
          }
        };
    public static final Level WARN =
        new Level() {
          @Override
          public int compareTo(final Level that) {
            return that == WARN ? 0 : (that == DEBUG || that == INFO || that == VERBOSE) ? 1 : -1;
          }

          @Override
          public String toString() {
            return "WARN";
          }
        };
    public static final Level ERROR =
        new Level() {
          @Override
          public int compareTo(final Level that) {
            return that == ERROR ? 0 : 1;
          }

          @Override
          public String toString() {
            return "ERROR";
          }
        };
    public static final Level VERBOSE =
        new Level() {
          @Override
          public int compareTo(final Level that) {
            return that == VERBOSE ? 0 : -1;
          }

          @Override
          public String toString() {
            return "VERBOSE";
          }
        };
  }

  private static class LoggerImpl implements Logger {
    private final Level level;
    private final OutputStream infoStream;
    private final OutputStream errorStream;
    private final Level errorLevel;

    LoggerImpl(
        final Level level,
        final OutputStream infoStream,
        final OutputStream errorStream,
        final Level errorLevel) {
      this.level = level;
      this.infoStream = infoStream;
      this.errorStream = errorStream;
      this.errorLevel = errorLevel;
    }

    @Override
    public Level getLevel() {
      return level;
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void verbose(final String message) {
      final OutputStream outputStream = errorLevel == Level.VERBOSE ? errorStream : infoStream;
      try {
        outputStream.write(message.getBytes());
        outputStream.write('\n');
      } catch (final IOException e) {
      }
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void debug(String message) {
      final OutputStream outputStream =
          (errorLevel.compareTo(Level.DEBUG) < 0) ? errorStream : infoStream;
      try {
        outputStream.write(message.getBytes());
        outputStream.write('\n');
      } catch (final IOException e) {
      }
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void info(String message) {
      final OutputStream outputStream =
          (errorLevel.compareTo(Level.INFO) < 0) ? errorStream : infoStream;
      try {
        outputStream.write(message.getBytes());
        outputStream.write('\n');
      } catch (final IOException e) {
      }
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void warn(String message) {
      final OutputStream outputStream =
          (errorLevel.compareTo(Level.WARN) < 0) ? errorStream : infoStream;
      try {
        outputStream.write(message.getBytes());
        outputStream.write('\n');
      } catch (final IOException e) {
      }
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void error(String message) {
      final OutputStream outputStream =
          (errorLevel.compareTo(Level.ERROR) < 0) ? errorStream : infoStream;
      try {
        outputStream.write(message.getBytes());
        outputStream.write('\n');
      } catch (final IOException e) {
      }
    }
  }

  public static Logger getLogger() {
    synchronized (lock) {
      if (global == null) {
        final Level level = Level.fromString(System.getProperty("swoval.log.level", "error"));
        Loggers.global = DefaultLogger.get(System.getProperty("swoval.logger"));
        if (Loggers.global == null) {
          Loggers.global = new LoggerImpl(level, System.out, System.err, Level.ERROR);
        }
      }
      return Loggers.global;
    }
  }

  public static void logException(final Logger logger, final Throwable t) {
    int i = 0;
    final StackTraceElement[] elements = t.getStackTrace();
    while (i < elements.length) {
      logger.error(elements[i].toString());
      i += 1;
    }
  }

  public static boolean shouldLog(final Logger logger, final Level level) {
    return logger.getLevel().compareTo(level) <= 0;
  }
}
