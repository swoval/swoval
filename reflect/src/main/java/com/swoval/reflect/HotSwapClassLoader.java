package com.swoval.reflect;

public interface HotSwapClassLoader {
    void addToCache(String name, Class<?> clazz);
    ClassLoader getParent();
    default void loadClassses() {
        ClassLoader loader = getParent();
        while (loader != null) {
            for (Class<?> clazz : Agent.instrumentation.getInitiatedClasses(loader)) {
                addToCache(clazz.getName(), clazz);
            }
            loader = loader.getParent();
        }
    }
}