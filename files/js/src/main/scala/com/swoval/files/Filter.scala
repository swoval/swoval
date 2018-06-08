package com.swoval.files

/**
 * Generic Filter functional interface. Primarily used by [[QuickList]].
 * @tparam T The type of object to filter
 */
trait Filter[T] {

  /**
   * Accept only some instances of {@code T}.
   * @param t The instance to filter
   * @return true if the instance is accepted
   */
  def accept(t: T): Boolean

}
