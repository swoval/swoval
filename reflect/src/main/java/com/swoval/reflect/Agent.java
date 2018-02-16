package com.swoval.reflect;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class Agent {
  private static Instrumentation instrumentation = null;

  public static Class[] getInitiatedClasses(ClassLoader loader) {
    return instrumentation == null ? new Class[0] : instrumentation.getInitiatedClasses(loader);
  }

  public static void premain(String args, Instrumentation inst) {
    instrumentation = inst;
    inst.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String className,
              Class<?> classBeingRedefined,
              ProtectionDomain protectionDomain,
              byte[] classfileBuffer)
              throws IllegalClassFormatException {
            if (className != null && className.startsWith("com/swoval/reflect")) {
              System.out.println(
                  "WTF loading " + className + " " + loader.toString().substring(0, 25));
              //                    for (StackTraceElement e : new Exception().getStackTrace()) {
              //                        System.out.println(e);
              //                    }
              //                    System.out.println("\n\n");
            }
            return new byte[0];
          }
        });
  }
}
