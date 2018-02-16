package com.swoval.reflect;

public interface HotSwapClassLoader {
  void addToCache(final String name, final Class<?> clazz);

  ClassLoader getParent();

  default void fillCache() {
    ClassLoader loader = getParent();
    System.out.println("FUCK ME " + loader);
    while (loader != null) {
      System.out.println("FUCK ME loop " + loader + " ? " + Agent.getInitiatedClasses(loader).length);
      for (Class<?> clazz : Agent.getInitiatedClasses(loader)) {
        if (clazz.getName().contains("com.swoval"))
          System.out.println("Adding " + clazz.getName() + " to cache");
        else
          System.out.println("Adding " + clazz.getName() + " to cache");
        addToCache(clazz.getName(), clazz);
      }
      loader = loader.getParent();
    }
  }
}
