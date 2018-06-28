package com.swoval.functional;

import java.io.IOException;

/**
 * A functional interface for performing IO and returning the result or an exception.
 *
 * @param <T> The input type
 * @param <R> The result type
 */
public interface IO<T, R> {

  /**
   * Invoke the IO task.
   *
   * @param t The input.
   * @return The result or the IOException that was thrown attempting to compute it.
   */
  Either<IOException, R> apply(T t);
}
