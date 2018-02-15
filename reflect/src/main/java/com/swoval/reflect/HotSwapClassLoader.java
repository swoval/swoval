package com.swoval.reflect;

public interface HotSwapClassLoader {
  void addToCache(final String name, final Class<?> clazz);

  ClassLoader getParent();

  default void fillCache() {
    ClassLoader loader = getParent();
    while (loader != null) {
      for (Class<?> clazz : Agent.getInitiatedClasses(loader)) {
        addToCache(clazz.getName(), clazz);
      }
      loader = loader.getParent();
    }
  }
}
