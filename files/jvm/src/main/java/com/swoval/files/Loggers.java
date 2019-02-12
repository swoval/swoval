package com.swoval.files;

import com.swoval.logging.Logger;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

class Loggers {
  private static DebugLogger debug = null;
  private static final Object lock = new Object();

  private static class DebugLoggerImpl implements DebugLogger {
    private final Logger logger;

    DebugLoggerImpl(final Logger logger) {
      this.logger = logger;
    }

    @Override
    public void debug(final String message) {
      if (logger != null) logger.debug(message);
    }

    @Override
    public boolean shouldLog() {
      return logger != null;
    }
  }

  @SuppressWarnings("unchecked")
  static DebugLogger getDebug() {
    synchronized (lock) {
      if (debug == null) {
        final boolean debug = Boolean.getBoolean("swoval.debug");
        String className = System.getProperty("swoval.debug.logger");
        Logger impl = null;
        if (debug || className != null) {
          if (className != null) {
            try {
              final Class<Logger> clazz = (Class<Logger>) Class.forName(className);
              final Constructor<Logger> constructor = clazz.getConstructor();
              impl = constructor.newInstance();
            } catch (final ClassNotFoundException
                | ClassCastException
                | NoSuchMethodException
                | InvocationTargetException
                | IllegalAccessException
                | InstantiationException e) {
              System.err.println("Couldn't instantiate an instance of " + className);
            }
          }
          if (impl == null) {
            impl =
                new Logger() {
                  @Override
                  public void debug(final String message) {
                    System.out.println(message);
                  }
                };
          }
          Loggers.debug = new DebugLoggerImpl(impl);
        } else {
          Loggers.debug =
              new DebugLogger() {
                @Override
                public boolean shouldLog() {
                  return false;
                }

                @Override
                public void debug(String message) {}

                @Override
                public String toString() {
                  return "com.swoval.files.Loggers.NullLogger";
                }
              };
        }
      }
      return Loggers.debug;
    }
  }
}
