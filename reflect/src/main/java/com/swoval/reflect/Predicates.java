package com.swoval.reflect;

import java.util.function.Predicate;

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

  public Predicate<String> getForceParent() {
    return forceParent;
  }

  public Predicate<String> getForceChild() {
    return forceChild;
  }
}
