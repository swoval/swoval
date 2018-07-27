// Do not edit this file manually. It is autogenerated.

package com.swoval.files

/**
 * Functional interface for an operation that takes two input arguments and returns no result.
 *
 * @tparam T the first input argument
 * @tparam U the second input argument
 */
trait BiConsumer[T, U] {

  /**
   * Performs the operation on the provided arguments.
   *
   * @param t the first argument
   * @param u the second argument
   */
  def accept(t: T, u: U): Unit

}