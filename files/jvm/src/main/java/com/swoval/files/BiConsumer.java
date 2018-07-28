package com.swoval.files;

/**
 * Functional interface for an operation that takes two input arguments and returns no result.
 *
 * @param <T> the first input argument
 * @param <U> the second input argument
 */
interface BiConsumer<T, U> {

  /**
   * Performs the operation on the provided arguments.
   *
   * @param t the first argument
   * @param u the second argument
   */
  void accept(final T t, final U u);
}
