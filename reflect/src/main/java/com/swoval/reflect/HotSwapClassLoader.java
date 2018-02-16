package com.swoval.reflect;

public interface HotSwapClassLoader {
  void addToCache(final String name, final Class<?> clazz);

  ClassLoader getParent();

  default void fillCache() {
    ClassLoader loader = getParent();
    System.out.println(loader);
    while (loader != null) {
      for (Class<?> clazz : Agent.getInitiatedClasses(loader)) {
        System.out.println("Adding clazz");
        addToCache(clazz.getName(), clazz);
      }
      loader = loader.getParent();
    }
  }
}
