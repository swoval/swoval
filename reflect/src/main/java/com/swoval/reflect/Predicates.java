package com.swoval.reflect;

import java.util.function.Predicate;

/**
 * Controls whether a ChildFirstClassLoader will use the parent or the child to load a particular
 * class. For now, it only supports inclusion rules. Example:
 * {{{
 *   URL[] urls = new URL[] { Paths.get("myjar.jar") }
 *   ClassLoader parent = Thread.currentThread().getContextClassLoader();
 *   Predicate<String> forceParent = name -> name.startsWith("com.acme.library.that.never.changes")
 *   Predicate<String> forceChild = name -> name.startsWith("com.myorg")
 *   Predicates predicates = new Predicates(forceParent, forceChile);
 *   ChildFirstClassLoader loader =
 *     new ChildFirstClassLoader(urls, parent, predicates, new HashMap<>());
 * }}}
 */
public class Predicates {
  private final Predicate<String> forceParent;
  private final Predicate<String> forceChild;
  private static final Predicate<String> defaultParent =
      name ->
          name.equals("com.swoval.reflect.ChildFirstClassLoader")
              || name.equals("com.swoval.reflect.Agent")
              || name.equals("com.swoval.reflect.HotSwapClassLoader")
              || name.equals("com.swoval.reflect.Predicates");
  private static final Predicate<String> defaultChild = name -> false;

  public static Predicates defaultPredicates() {
    return new Predicates(defaultParent, defaultChild);
  }

  public Predicates(Predicate<String> forceParent, Predicate<String> forceChild) {
    this.forceParent = forceParent;
    this.forceChild = forceChild;
  }

  /**
   * Getter for the forceParent Predicate.
   * @return
   */
  public Predicate<String> getForceParent() {
    return forceParent;
  }

  /**
   * Getter for the forceChild Predicate.
   * @return
   */
  public Predicate<String> getForceChild() {
    return forceChild;
  }
}
