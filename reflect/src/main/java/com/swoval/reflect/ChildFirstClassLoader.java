package com.swoval.reflect;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public class ChildFirstClassLoader extends URLClassLoader implements HotSwapClassLoader {
  public static class Predicates {
    private final Predicate<String> forceParent;
    private final Predicate<String> forceChild;

    public Predicates(Predicate<String> forceParent, Predicate<String> forceChild) {
      this.forceParent = forceParent;
      this.forceChild = forceChild;
    }

    public Predicate<String> getForceParent() {
      return forceParent;
    }

    public Predicate<String> getForceChild() {
      return forceChild;
    }
  }

  private final Map<String, Class<?>> loaded;
  private final Predicates predicates;
  private static final Predicates DEFAULT_PREDICATES =
      new Predicates(
          name ->
              name.startsWith("java.")
                  || name.startsWith("sun.")
                  || name.startsWith("com.swoval.reflect"),
          name -> false);

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
    this(urls, DEFAULT_PREDICATES, Thread.currentThread().getContextClassLoader(), new HashMap<>());
  }

  public ChildFirstClassLoader(final URL[] urls, final ClassLoader parent) {
    this(urls, DEFAULT_PREDICATES, parent, new HashMap<>());
  }

  public ChildFirstClassLoader(final ClassLoader parent) {
    this(new URL[0], DEFAULT_PREDICATES, parent, new HashMap<>());
  }

  @Override
  public Class<?> loadClass(final String name, final boolean resolve)
      throws ClassNotFoundException {
    Class<?> clazz = loaded.get(name);
    if (name.equals("com.swoval.reflect.Buzz")) {
      for (Map.Entry<String, Class<?>> entry : loaded.entrySet()) {
        //        if (entry.getValue().getName().contains("swoval")) {
        //          System.out.println("" + this + " WTF " + entry);
        //        }
      }
      System.out.println("Loading buzz " + clazz);
    }
    if (clazz != null) {
      return clazz;
    }
    if (!predicates.forceParent.test(name) || predicates.forceChild.test(name)) {
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

  @Override
  public Class<?> loadClass(final String name) throws ClassNotFoundException {
    return loadClass(name, false);
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

  @Override
  public void addToCache(String name, Class<?> clazz) {
    loaded.put(name, clazz);
  }

  public Map<String, Class<?>> getLoadedClasses() {
    return Collections.unmodifiableMap(loaded);
  }
}
