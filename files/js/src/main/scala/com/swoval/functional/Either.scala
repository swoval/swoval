package com.swoval.functional

import Either._

object Either {

  def left[L, R](value: L): Either[L, R] = new Left(value)

  def right[L, R](value: R): Either[L, R] = new Right(value)

  class NotLeftException extends RuntimeException

  class NotRightException extends RuntimeException

  private class Left[L, R](private val value: L) extends Either[L, R] {

    override def left(): L = value

    override def right(): R = throw new NotRightException()

    override def isLeft(): Boolean = true

    override def isRight(): Boolean = false

  }

  private class Right[L, R](private val value: R) extends Either[L, R] {

    override def left(): L = throw new NotLeftException()

    override def right(): R = value

    override def isLeft(): Boolean = false

    override def isRight(): Boolean = true

  }

}

/**
 * Represents a value that can be one of two types
 *
 * @tparam L The left value
 * @tparam R The right value
 */
abstract class Either[L, R] private () {

  def left(): L

  def right(): R

  def isLeft(): Boolean

  def isRight(): Boolean

}
