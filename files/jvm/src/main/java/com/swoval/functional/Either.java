package com.swoval.functional;

/**
 * Represents a value that can be one of two types. Inspired by <a
 * href="https://www.scala-lang.org/api/current/scala/util/Either.html" target="_blank">Either</a>,
 * it is right biased, but does not define all of the combinators that the scala version does.
 *
 * @param <L> The left value
 * @param <R> The right value
 */
public abstract class Either<L, R> {
  private Either() {}

  /**
   * Returns the Left projection
   *
   * @return a Left projection
   * @throws NotLeftException if the value is a left
   */
  public abstract Left<L, R> left() throws NotLeftException;

  /**
   * Returns the Right projection
   *
   * @return a Right projection
   * @throws NotRightException if the value is a right
   */
  public abstract Right<L, R> right() throws NotRightException;

  /**
   * Check whether this is a Left projection.
   *
   * @return true if this is a Reft projection
   */
  public abstract boolean isLeft();

  /**
   * Check whether this is a Right projection.
   *
   * @return true if this is a Right projection
   */
  public abstract boolean isRight();

  /**
   * Get the right projected value of the either. This is unsafe to call without checking whether
   * the value is a right first.
   *
   * @return the wrapped value if is a right projection
   * @throws NotRightException if this is a left projection
   */
  public R get() {
    if (isRight()) return right().getValue();
    else throw new NotRightException();
  }

  /**
   * Get the right projected value of the either or a provided default value.
   *
   * @param r the default value
   * @return the wrapped value if this is a right projection, otherwise the default
   */
  public R getOrElse(final R r) {
    return isRight() ? right().getValue() : r;
  }

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(final Object other);

  /**
   * Casts an either to a more specific left type
   *
   * @param clazz The left type to downcast to
   * @param <L> The original left type
   * @param <R> The right type
   * @param <T> The downcasted left type
   * @return The original either with the left type downcasted to T
   * @throws ClassCastException if the wrapped value is not a subtype of T
   */
  @SuppressWarnings("unchecked")
  public <L, R, T extends L> Either<T, R> castLeft(final Class<T> clazz) {
    if (isRight()) return (Either<T, R>) this;
    else if (clazz.isAssignableFrom(left().getValue().getClass())) return (Either<T, R>) this;
    else throw new ClassCastException(left() + " is not an instance of " + clazz);
  }

  /**
   * Casts an either to a more specific right type
   *
   * @param clazz The left type to downcast to
   * @param <L> The original left type
   * @param <R> The right type
   * @param <T> The downcasted right type
   * @return The original either with the right type downcasted to T
   * @throws ClassCastException if the wrapped value is not a subtype of T
   */
  @SuppressWarnings("unchecked")
  public <L, R, T extends R> Either<L, T> castRight(final Class<T> clazz) {
    if (this.isLeft()) return (Either<L, T>) this;
    else if (clazz.isAssignableFrom(right().getValue().getClass())) return (Either<L, T>) this;
    else throw new ClassCastException(right() + " is not an instance of " + clazz);
  }

  /**
   * Returns a left projected either
   *
   * @param value The value to wrap
   * @param <L> The type of the left parameter of the result
   * @param <R> The type of the right parameter of the result
   * @param <T> A refinement type that allows us to wrap subtypes of L
   * @return A left projected either
   */
  public static <L, R, T extends L> Either<L, R> left(final T value) {
    return new Left<>((L) value);
  }

  /**
   * Returns a right projected either
   *
   * @param value The value to wrap
   * @param <L> The type of the left parameter of the result
   * @param <R> The type of the right parameter of the result
   * @param <T> A refinement type that allows us to wrap subtypes of R
   * @return A right projected either
   */
  public static <L, R, T extends R> Either<L, R> right(final T value) {
    return new Right<>((R) value);
  }

  public static class NotLeftException extends RuntimeException {}

  public static class NotRightException extends RuntimeException {}

  public static final class Left<L, R> extends Either<L, R> {
    private final L value;

    Left(final L value) {
      this.value = value;
    }

    /**
     * Returns the wrapped value
     *
     * @return the wrapped value
     */
    public L getValue() {
      return value;
    }

    @Override
    public Left<L, R> left() {
      return this;
    }

    @Override
    public Right<L, R> right() {
      throw new NotRightException();
    }

    @Override
    public boolean isLeft() {
      return true;
    }

    @Override
    public boolean isRight() {
      return false;
    }

    @Override
    public String toString() {
      return "Left(" + value + ")";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof com.swoval.functional.Either.Left<?, ?>
          && this.value.equals(((com.swoval.functional.Either.Left<?, ?>) other).getValue());
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  public static final class Right<L, R> extends Either<L, R> {
    private final R value;

    Right(final R value) {
      this.value = value;
    }

    /**
     * Returns the wrapped value
     *
     * @return the wrapped value
     */
    public R getValue() {
      return value;
    }

    @Override
    public Left<L, R> left() {
      throw new NotLeftException();
    }

    @Override
    public Right<L, R> right() {
      return this;
    }

    @Override
    public boolean isLeft() {
      return false;
    }

    @Override
    public boolean isRight() {
      return true;
    }

    @Override
    public String toString() {
      return "Right(" + value + ")";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof com.swoval.functional.Either.Right<?, ?>
          && this.value.equals(((com.swoval.functional.Either.Right<?, ?>) other).getValue());
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }
}
