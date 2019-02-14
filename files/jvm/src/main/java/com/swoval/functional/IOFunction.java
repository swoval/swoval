package com.swoval.functional;

import java.io.IOException;

/**
 * Represents a one argument function.
 *
 * @param <T> the input type
 * @param <R> the output type
 */
public interface IOFunction<T, R> {
  /**
   * Apply the function to the input.
   *
   * @param t the input value
   * @return the output value
   */
  R apply(final T t) throws IOException;
}
