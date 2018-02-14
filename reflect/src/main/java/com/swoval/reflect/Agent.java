package com.swoval.reflect;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


public class Agent {
    private static final Map<ClassLoader, Set<String>> loadedClasses = new WeakHashMap<>();
    private static final Object lock = new Object();
    public static Instrumentation instrumentation = null;
    public static void premain(String args, Instrumentation inst) {
        System.out.println(args);
        instrumentation = inst;
//        inst.addTransformer((loader, className, unused, protectionDomain, buffer) -> {
//            ClassLoader l = protectionDomain == null ? null : protectionDomain.getClassLoader();
//            l = l == null ? loader : l;
//            if (className != null && l != null) {
//                synchronized (lock) {
//                    Set<String> set = loadedClasses.computeIfAbsent(l, x -> new HashSet<>());
//                    set.add(className.replaceAll("/", "."));
//                }
//            }
//            return buffer;
//        });
    }
//    public static <T extends ClassLoader & HotSwapClassLoader> void loadCache(T loader) {
//        final T init = loader;
//        final long start = System.nanoTime();
//        //System.out.println("Checking class " + name + " " + loader);
//        boolean res = false;
//        while (loader != null && !res) {
//            Class[] classes = instrumentation.getInitiatedClasses(loader);
//            //System.out.println("loader " + loader + " len: " + classes.length);
//            for(Class<?> clazz : classes) {
//                if (clazz.getName().equals(name)) {
//                    res = true;
//                    break;
//                }
//            }
//            loader = loader.getParent();
//        }
//        final long elapsed = System.nanoTime() - start;
//        System.out.println("" + init + " already loaded class " + name + "? " + res + " (" +
//                (elapsed / 1e6) + " ms)");
//        return res;
//    }
}
