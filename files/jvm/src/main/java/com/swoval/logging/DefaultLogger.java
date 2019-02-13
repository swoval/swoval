package com.swoval.logging;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

class DefaultLogger {
  @SuppressWarnings("unchecked")
  static Logger get(final String className) {
    if (className != null) {
      try {
        final Class<Logger> clazz = (Class<Logger>) Class.forName(className);
        final Constructor<Logger> constructor = clazz.getConstructor();
        return constructor.newInstance();
      } catch (final ClassNotFoundException
          | ClassCastException
          | NoSuchMethodException
          | InvocationTargetException
          | IllegalAccessException
          | InstantiationException e) {
        System.err.println("Couldn't instantiate an instance of " + className);
      }
    }
    return null;
  }
}
