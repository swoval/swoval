package com.swoval.functional

import Either._
import scala.beans.{ BeanProperty, BooleanBeanProperty }

object Either {

  /**
   * Returns a left projected either
   *
   * @param value The value to wrap
   * @tparam L The type of the left parameter of the result
   * @tparam R The type of the right parameter of the result
   * @tparam T A refinement type that allows us to wrap subtypes of L
   * @return A left projected either
   */
  def left[L, R, T <: L](value: T): Either[L, R] =
    new Left(value.asInstanceOf[L])

  /**
   * Returns a right projected either
   *
   * @param value The value to wrap
   * @tparam L The type of the left parameter of the result
   * @tparam R The type of the right parameter of the result
   * @tparam T A refinement type that allows us to wrap subtypes of R
   * @return A right projected either
   */
  def right[L, R, T <: R](value: T): Either[L, R] =
    new Right(value.asInstanceOf[R])

  class NotLeftException extends RuntimeException

  class NotRightException extends RuntimeException

  class Left[L, R](@BeanProperty val value: L) extends Either[L, R] {

    override def left(): Left[L, R] = this

    override def right(): Right[L, R] = throw new NotRightException()

    override def isLeft(): Boolean = true

    override def isRight(): Boolean = false

    override def toString(): String = "Left(" + value + ")"

    override def equals(other: Any): Boolean = other match {
      case other: com.swoval.functional.Either.Left[_, _] =>
        this.value == other.getValue
      case _ => false

    }

    override def hashCode(): Int = value.hashCode

  }

  class Right[L, R](@BeanProperty val value: R) extends Either[L, R] {

    override def left(): Left[L, R] = throw new NotLeftException()

    override def right(): Right[L, R] = this

    override def isLeft(): Boolean = false

    override def isRight(): Boolean = true

    override def toString(): String = "Right(" + value + ")"

    override def equals(other: Any): Boolean = other match {
      case other: com.swoval.functional.Either.Right[_, _] =>
        this.value == other.getValue
      case _ => false

    }

    override def hashCode(): Int = value.hashCode

  }

}

/**
 * Represents a value that can be one of two types. Inspired by [[https://www.scala-lang.org/api/current/scala/util/Either.html Either]],
 * it is right biased, but does not define all of the combinators that the scala version does.
 *
 * @tparam L The left value
 * @tparam R The right value
 */
abstract class Either[L, R] private () {

  /**
   * Returns the Left projection
   *
   * @return a Left projection
   */
  def left(): Left[L, R]

  /**
   * Returns the Right projection
   *
   * @return a Right projection
   */
  def right(): Right[L, R]

  /**
   * Check whether this is a Left projection.
   *
   * @return true if this is a Reft projection
   */
  def isLeft(): Boolean

  /**
   * Check whether this is a Right projection.
   *
   * @return true if this is a Right projection
   */
  def isRight(): Boolean

  /**
   * Get the right projected value of the either. This is unsafe to call without checking whether
   * the value is a right first.
   *
   * @return the wrapped value if is a right projection
   */
  def get(): R =
    if (isRight) right().getValue else throw new NotRightException()

  /**
   * Get the right projected value of the either or a provided default value.
   *
   * @param r the default value
   * @return the wrapped value if this is a right projection, otherwise the default
   */
  def getOrElse(r: R): R = if (isRight) right().getValue else r

  override def hashCode(): Int

  override def equals(other: Any): Boolean

  /**
   * Casts an either to a more specific left type
   *
   * @param clazz The left type to downcast to
   * @tparam L The original left type
   * @tparam R The right type
   * @tparam T The downcasted left type
   * @return The original either with the left type downcasted to T
   */
  def castLeft[L, R, T <: L](clazz: Class[T]): Either[T, R] =
    if (isRight) this.asInstanceOf[Either[T, R]]
    else if (clazz.isAssignableFrom(left().getValue.getClass))
      this.asInstanceOf[Either[T, R]]
    else
      throw new ClassCastException(left() + " is not an instance of " + clazz)

  /**
   * Casts an either to a more specific right type
   *
   * @param clazz The left type to downcast to
   * @tparam L The original left type
   * @tparam R The right type
   * @tparam T The downcasted right type
   * @return The original either with the right type downcasted to T
   */
  def castRight[L, R, T <: R](clazz: Class[T]): Either[L, T] =
    if (this.isLeft) this.asInstanceOf[Either[L, T]]
    else if (clazz.isAssignableFrom(right().getValue.getClass))
      this.asInstanceOf[Either[L, T]]
    else
      throw new ClassCastException(right() + " is not an instance of " + clazz)

}
