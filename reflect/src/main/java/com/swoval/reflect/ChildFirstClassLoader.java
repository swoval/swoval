package com.swoval.reflect;

import static com.swoval.reflect.Predicates.defaultPredicates;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class ChildFirstClassLoader extends URLClassLoader {
  private final Map<String, Class<?>> loaded;
  private final Predicates predicates;
  private final URL[] urls;

  public ChildFirstClassLoader(
      final URL[] urls,
      final Predicates predicates,
      final ClassLoader parent,
      final Map<String, Class<?>> loaded) {
    super(urls, parent);
    this.loaded = loaded;
    this.urls = urls;
    this.predicates = predicates;
    if (loaded.isEmpty()) fillCache();
  }

  public ChildFirstClassLoader(final URL[] urls) {
    this(
        urls, defaultPredicates(), Thread.currentThread().getContextClassLoader(), new HashMap<>());
  }

  public ChildFirstClassLoader(final URL[] urls, final ClassLoader parent) {
    this(urls, defaultPredicates(), parent, new HashMap<>());
  }

  public ChildFirstClassLoader(final ClassLoader parent) {
    this(new URL[0], defaultPredicates(), parent, new HashMap<>());
  }

  @Override
  public Class<?> loadClass(final String name, final boolean resolve)
      throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> clazz = loaded.get(name);
      if (clazz != null) {
        return clazz;
      }
      if (name.startsWith("java.") || name.startsWith("sun.")
          || (predicates.getForceParent().test(name) && !predicates.getForceChild().test(name))) {
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
      loaded.put(name, clazz);
      return clazz;
    }
  }

  @Override
  public Class<?> loadClass(final String name) throws ClassNotFoundException {
    return loadClass(name, false);
  }

  public void fillCache() {
    ClassLoader loader = getParent();
    while (loader != null) {
      for (Class<?> clazz : Agent.getInitiatedClasses(loader)) {
        loaded.put(clazz.getName(), clazz);
      }
      loader = loader.getParent();
    }
  }
  public ChildFirstClassLoader copy(URL[] urls) {
    return new ChildFirstClassLoader(urls, predicates, getParent(), new HashMap<>(loaded));
  }

  public ChildFirstClassLoader copy(Function<Predicates, Predicates> func) {
    return new ChildFirstClassLoader(
        urls, func.apply(predicates), getParent(), new HashMap<>(loaded));
  }

  public ChildFirstClassLoader dup() {
    return new ChildFirstClassLoader(urls, predicates, getParent(), new HashMap<>(loaded));
  }

  @Override
  public String toString() {
    StringBuilder urlString = new StringBuilder();
    urlString.append('[');
    for (URL u : urls) urlString.append(u.toString()).append(',');
    urlString.append(']');
    return "ChildFirstClassLoader(" + urlString + ", " + getParent() + ")";
  }

  // This is necessary to use this class as a system classloader in java 9.
  public void appendToClassPathForInstrumentation(String name) {
    try {
      super.addURL(Paths.get(name).toUri().toURL());
    } catch (MalformedURLException e) {
      throw new InternalError(e);
    }
  }

  public Map<String, Class<?>> getLoadedClasses() {
    return Collections.unmodifiableMap(loaded);
  }
}
