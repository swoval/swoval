package com.swoval.reflect;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


public class Agent {
    public static Instrumentation instrumentation = null;
    public static Class[] getInitiatedClasses(ClassLoader loader) {
        return instrumentation == null ? new Class[0] : instrumentation.getInitiatedClasses(loader);
    }
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }
}
