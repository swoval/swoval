package com.swoval.reflect;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


public class Agent {
    private static Instrumentation instrumentation = null;
    public static Class[] getInitiatedClasses(ClassLoader loader) {
        return instrumentation == null ? new Class[0] : instrumentation.getInitiatedClasses(loader);
    }
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
//        inst.addTransformer(new ClassFileTransformer() {
//            @Override
//            public byte[] transform(ClassLoader loader, String className,
//                Class<?> classBeingRedefined,
//                ProtectionDomain protectionDomain, byte[] classfileBuffer)
//                throws IllegalClassFormatException {
//                if (className != null && className.startsWith("com/swoval"))
//                    System.out.println("Loaded " + className + " " + loader);
//                return classfileBuffer;
//            }
//        });
    }
}
