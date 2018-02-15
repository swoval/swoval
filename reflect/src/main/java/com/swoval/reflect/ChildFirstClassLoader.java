package com.swoval.reflect;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ChildFirstClassLoader extends URLClassLoader implements HotSwapClassLoader {
  private final Map<String, Class<?>> loaded;

  private final URL[] urls;

  public ChildFirstClassLoader(
      final URL[] urls, final ClassLoader parent, final Map<String, Class<?>> loaded) {
    super(urls, parent);
    this.loaded = loaded;
    this.urls = urls;
    if (loaded.isEmpty()) fillCache();
  }

  public ChildFirstClassLoader(final URL[] urls) {
    this(urls, Thread.currentThread().getContextClassLoader(), new HashMap<>());
  }

  public ChildFirstClassLoader(final URL[] urls, final ClassLoader parent) {
    this(urls, parent, new HashMap<>());
  }

  public ChildFirstClassLoader(final ClassLoader parent) {
    this(new URL[0], parent, new HashMap<>());
  }

  @Override
  public Class<?> loadClass(final String name, final boolean resolve)
      throws ClassNotFoundException {
    Class<?> clazz = loaded.get(name);
    if (clazz != null) {
      return clazz;
    }
    if (name.startsWith("java.") || name.startsWith("sun.")) {
      clazz = getParent().loadClass(name);
    } else {
      try {
        clazz = findClass(name);
      } catch (final ClassNotFoundException e) {
        clazz = getParent().loadClass(name);
      }
    }
    if (resolve) {
      resolveClass(clazz);
    }
    System.out.println("Adding " + name);
    loaded.put(name, clazz);
    return clazz;
  }

  @Override
  public Class<?> loadClass(final String name) throws ClassNotFoundException {
    return loadClass(name, false);
  }

  public ChildFirstClassLoader copy(URL[] urls) {
    return new ChildFirstClassLoader(urls, getParent(), new HashMap<>(loaded));
  }

  public ChildFirstClassLoader dup() {
    return new ChildFirstClassLoader(urls, getParent(), new HashMap<>(loaded));
  }

  @Override
  public void addToCache(String name, Class<?> clazz) {
    loaded.put(name, clazz);
  }

  public Map<String, Class<?>> getLoadedClasses() {
    return Collections.unmodifiableMap(loaded);
  }
}
