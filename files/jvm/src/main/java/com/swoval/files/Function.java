package com.swoval.files;

/** Functional interface for a function that takes one argument. */
interface Function<T, R> {

  /**
   * Returns the result of applying the function to the input parameter.
   *
   * @param t the input
   * @return the result of applying the function.
   * @throws Exception when there is an error.
   */
  R apply(final T t) throws Exception;
}
