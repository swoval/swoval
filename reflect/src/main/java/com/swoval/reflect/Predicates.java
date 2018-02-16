package com.swoval.reflect;

import java.util.function.Predicate;

public class Predicates {
  private final Predicate<String> forceParent;
  private final Predicate<String> forceChild;
  private static final Predicate<String> defaultParent = new Predicate<String>() {
    @Override
    public boolean test(String name) {
      return name.startsWith("java.") || name.startsWith("sun.") || name.startsWith("com.swoval.reflect.");
    }
  };
  private static final Predicate<String> defaultChild = new Predicate<String>() {
    @Override
    public boolean test(String name) {
      return false;
    }
  };
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
