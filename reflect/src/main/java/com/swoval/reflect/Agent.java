package com.swoval.reflect;

import java.lang.instrument.Instrumentation;

/**
 * Simple java agent implementation that provides access to the loaded classes of a particular class
 * loader. The only method is getInitiatedClasses, which delegates to
 * Instrumentation#getInitiatedClasses. It will typically be used recursively, e.g.
 *
 * <p>{{{
 *  ClassLoader loader = Thread.currentThread().getContextClassLoader();
 *  List<Class<?>> result = new ArrayList<>();
 *  while (loader != null) {
 *    for (Class<?> clazz : com.swoval.reflect.Agent.getInitiatedClass(loader)) {
 *      result.add(clazz);
 *    }
 *    loader = loader.getParent();
 *  }
 *  }}}
 */
public class Agent {
  private static Instrumentation instrumentation = null;

  /**
   * Get an array of loaded classes. The user will typically want to use this recursively, e.g.
   *
   * @param loader
   * @return the loaded classes
   */
  public static Class[] getInitiatedClasses(ClassLoader loader) {
    return instrumentation == null ? new Class[0] : instrumentation.getInitiatedClasses(loader);
  }

  public static void premain(String args, Instrumentation inst) {
    instrumentation = inst;
  }
}
