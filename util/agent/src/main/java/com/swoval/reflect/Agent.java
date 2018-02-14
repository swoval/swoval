package com.swoval.reflect;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


public class Agent {
    private static final Map<ClassLoader, Set<String>> loadedClasses = new WeakHashMap<>();
    private static final Object lock = new Object();
    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer((loader, className, unused, protectionDomain, buffer) -> {
            ClassLoader l = protectionDomain == null ? null : protectionDomain.getClassLoader();
            l = l == null ? loader : l;
            if (className != null && l != null) {
                synchronized (lock) {
                    Set<String> set = loadedClasses.computeIfAbsent(l, x -> new HashSet<>());
                    set.add(className.replaceAll("/", "."));
                }
            }
            return buffer;
        });
    }
    public static boolean hasParentLoadedClass(ClassLoader loader, String name) {
        final ClassLoader init = loader;
        boolean res = false;
        while (loader != null && !res) {
            synchronized (lock) {
                res = loadedClasses.getOrDefault(loader, new HashSet<>()).contains(name);
            }
            loader = loader.getParent();
        }
        System.out.println("" + init + " loaded " + name + "? " + res);
        return res;
    }
}
