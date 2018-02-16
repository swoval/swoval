package com.swoval.reflect;

import java.lang.instrument.Instrumentation;

public class Agent {
  private static Instrumentation instrumentation = null;

  public static Class[] getInitiatedClasses(ClassLoader loader) {
    return instrumentation == null ? new Class[0] : instrumentation.getInitiatedClasses(loader);
  }

  public static void premain(String args, Instrumentation inst) {
    instrumentation = inst;
  }
}
