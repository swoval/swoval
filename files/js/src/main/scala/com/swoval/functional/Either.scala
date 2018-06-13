package com.swoval.functional

import Either._

object Either {

  def left[L, R, T <: L](value: T): Either[L, R] =
    new Left(value.asInstanceOf[L])

  def right[L, R, T <: R](value: T): Either[L, R] =
    new Right(value.asInstanceOf[R])

  class NotLeftException extends RuntimeException

  class NotRightException extends RuntimeException

  private class Left[L, R](private val value: L) extends Either[L, R] {

    override def left(): L = value

    override def right(): R = throw new NotRightException()

    override def isLeft(): Boolean = true

    override def isRight(): Boolean = false

    override def toString(): String = "Left(" + value + ")"

  }

  private class Right[L, R](private val value: R) extends Either[L, R] {

    override def left(): L = throw new NotLeftException()

    override def right(): R = value

    override def isLeft(): Boolean = false

    override def isRight(): Boolean = true

    override def toString(): String = "Right(" + value + ")"

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

  def castLeft[L, R, T <: L](clazz: Class[T]): Either[T, R] =
    if (isRight) this.asInstanceOf[Either[T, R]]
    else if (clazz.isAssignableFrom(left().getClass))
      this.asInstanceOf[Either[T, R]]
    else
      throw new ClassCastException(left() + " is not an instance of " + clazz)

  def castRight[L, R, T <: R](clazz: Class[T]): Either[L, T] =
    if (this.isLeft) this.asInstanceOf[Either[L, T]]
    else if (clazz.isAssignableFrom(right().getClass))
      this.asInstanceOf[Either[L, T]]
    else
      throw new ClassCastException(right() + " is not an instance of " + clazz)

}
