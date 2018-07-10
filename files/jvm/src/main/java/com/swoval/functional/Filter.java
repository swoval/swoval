package com.swoval.functional;

/**
 * Generic Filter functional interface.
 *
 * @param <T> The type of object to filter
 */
public interface Filter<T> {

  /**
   * Accept only some instances of {@code T}.
   *
   * @param t The instance to filter
   * @return true if the instance is accepted
   */
  boolean accept(final T t);
}
