package com.swoval.functional

/**
 * Represents an operation that takes an input and returns no result
 *
 * @tparam T The input type
 */
trait Consumer[T] {

  def accept(t: T): Unit

}
