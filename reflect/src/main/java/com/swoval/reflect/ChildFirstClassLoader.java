package com.swoval.reflect;

import static com.swoval.reflect.Predicates.defaultPredicates;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Provides a class loader that will find classes in the provided urls before delegating to the
 * parent class loader. System classes in the java.* and sun.* packages will always be loaded by the
 * parent. The user may force the parent or child to load non system classes using
 * {@link Predicates}.
 */
public class ChildFirstClassLoader extends URLClassLoader {
  /*
   * The loaded classes cache is so that if the URLClassLoader is able to reach classes that have
   * already been loaded by one of its parents, that it doesn't try to reload the class. There can
   * be a lot of headaches with incompatible class instances being passed around without this
   * cache.
   */
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

  /**
   * Default constructor that allows the ChildFirstClassLoader to be used as the java system class
   * loader.
   *
   * @param parent The parent classloader.
   */
  public ChildFirstClassLoader(final ClassLoader parent) {
    this(new URL[0], defaultPredicates(), parent, new HashMap<>());
  }

  /**
   * Create a new ChildFirstClassLoader with updated set of URLs and a deep copy of the loaded
   * classes.
   *
   * @param urls The new set of urls
   * @return The new ChildFirstClassLoader
   */
  public ChildFirstClassLoader copy(final URL[] urls) {
    return new ChildFirstClassLoader(urls, predicates, getParent(), new HashMap<>(loaded));
  }

  /**
   * Create a new ChildFirstClassLoader with updated predicates and a deep copy of the loaded
   * classes.
   *
   * @param func Transforms the existing predicates inta a new set of predicates.
   * @return The new ChildFirstClassLoader with updated predicates
   */
  public ChildFirstClassLoader copy(final Function<Predicates, Predicates> func) {
    return new ChildFirstClassLoader(
        urls, func.apply(predicates), getParent(), new HashMap<>(loaded));
  }

  /**
   * Copys this ChildFirstClassLoader with a deep copy of the loaded classes.
   *
   * @return The new ChildClassFirstClassLoader
   */
  public ChildFirstClassLoader copy() {
    return new ChildFirstClassLoader(urls, predicates, getParent(), new HashMap<>(loaded));
  }

  @Override
  public Class<?> loadClass(final String name, final boolean resolve)
      throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> clazz = loaded.get(name);
      if (clazz != null) {
        return clazz;
      }
      if (name.startsWith("java.")
          || name.startsWith("sun.")
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

  private void fillCache() {
    ClassLoader loader = getParent();
    while (loader != null) {
      for (Class<?> clazz : Agent.getInitiatedClasses(loader)) {
        loaded.put(clazz.getName(), clazz);
      }
      loader = loader.getParent();
    }
  }

  @Override
  public String toString() {
    final StringBuilder urlString = new StringBuilder();
    urlString.append('[');
    for (URL u : urls) urlString.append(u.toString()).append(',');
    urlString.append(']');
    return "ChildFirstClassLoader(" + urlString + ", " + getParent() + ")";
  }

  /**
   * See
   * [[https://docs.oracle.com/javase/9/docs/api/java/lang/instrument/Instrumentation.html#appendToSystemClassLoaderSearch-java.util.jar.JarFile-
   * java.lang.instrument.Instrumentation#appendToSystemClassLoaderSearch]]
   *
   * @param name
   */
  public void appendToClassPathForInstrumentation(String name) {
    try {
      super.addURL(Paths.get(name).toUri().toURL());
    } catch (MalformedURLException e) {
      throw new InternalError(e);
    }
  }
}
