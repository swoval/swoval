package com.swoval.functional;

/**
 * Represents an operation that takes an input and returns no result.
 *
 * @param <T> the input type
 */
public interface Consumer<T> {

  /**
   * Performs the operation on the given argument.
   *
   * @param t the input argument
   */
  void accept(final T t);
}
