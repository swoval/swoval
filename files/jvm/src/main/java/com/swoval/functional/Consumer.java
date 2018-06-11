package com.swoval.functional;

/**
 * Represents an operation that takes an input and returns no result
 *
 * @param <T> The input type
 */
public interface Consumer<T> {
  void accept(final T t);
}
