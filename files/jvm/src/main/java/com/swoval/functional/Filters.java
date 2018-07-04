package com.swoval.functional;

/**
 * Provides a generic AllPass filter.
 */
public class Filters {
  public static final Filter<Object> AllPass =
      new Filter<Object>() {
        @Override
        public boolean accept(Object o) {
          return true;
        }
      };
}
