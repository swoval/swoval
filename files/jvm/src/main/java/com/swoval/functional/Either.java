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
   * Returns the Left projection for the provided Either or throws an exception if the Either is
   * actually an instance of {@link com.swoval.functional.Either.Right}.
   *
   * @param either the either, assumed to be an instance of left, that will
   * @param <L> the left type of the either.
   * @param <R> the right type of the either.
   * @return a Left projection.
   * @throws NotLeftException if the value is a {@link com.swoval.functional.Either.Right}.
   */
  public static <L, R> Left<L, R> leftProjection(final Either<L, R> either)
      throws NotLeftException {
    if (either.isLeft()) return (Left<L, R>) either;
    else throw new NotLeftException();
  }

  /**
   * Returns the Right projection for the provided Either or throws an exception if the Either is
   * actually an instance of {@link com.swoval.functional.Either.Left}.
   *
   * @param either the either, assumed to be an instance of left, that will
   * @param <L> the left type of the either.
   * @param <R> the right type of the either.
   * @return a Right projection.
   * @throws NotRightException if the value is a {@link com.swoval.functional.Either.Left}.
   */
  public static <L, R> Right<L, R> rightProjection(final Either<L, R> either)
      throws NotRightException {
    if (either.isRight()) return (Right<L, R>) either;
    else throw new NotRightException();
  }

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
    if (isRight()) return rightProjection(this).getValue();
    else throw new NotRightException();
  }

  /**
   * Get the right projected value of the either or a provided default value.
   *
   * @param either the either from which the method extracts the result if it is a {@link
   *     com.swoval.functional.Either.Right}.
   * @param t the default value
   * @param <T> the default type
   * @return the wrapped value if this is a right projection, otherwise the default
   */
  public static <T> T getOrElse(final Either<?, ? extends T> either, final T t) {
    return either.isRight() ? either.get() : t;
  }

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(final Object other);

  /**
   * Casts an either to a more specific left type.
   *
   * @param clazz the left type to which we downcast
   * @param <L> the original left type
   * @param <R> the right type
   * @param <T> the downcasted left type
   * @return the original either with the left type downcasted to T.
   * @throws ClassCastException if the wrapped value is not a subtype of T.
   */
  @SuppressWarnings("unchecked")
  public <L, R, T extends L> Either<T, R> castLeft(final Class<T> clazz, final R defaultValue) {
    if (isRight()) {
      return (Either<T, R>) this;
    } else if (clazz.isAssignableFrom(leftProjection(this).getValue().getClass())) {
      return (Either<T, R>) this;
    } else {
      return Either.right(defaultValue);
    }
  }

  /**
   * Casts an either to a more specific right type.
   *
   * @param clazz The right type to which we downcast
   * @param <L> The original left type
   * @param <R> The right type
   * @param <T> The downcasted right type
   * @return The original either with the right type downcasted to T.
   * @throws ClassCastException if the wrapped value is not a subtype of T.
   */
  @SuppressWarnings("unchecked")
  public <L, R, T extends R> Either<L, T> castRight(final Class<T> clazz) {
    if (this.isLeft()) return (Either<L, T>) this;
    else if (clazz.isAssignableFrom(get().getClass())) return (Either<L, T>) this;
    else throw new ClassCastException(rightProjection(this) + " is not an instance of " + clazz);
  }

  /**
   * Returns a left projected either.
   *
   * @param value the value to wrap
   * @param <L> the type of the left parameter of the result
   * @param <R> the type of the right parameter of the result
   * @param <T> a refinement type that allows us to wrap subtypes of L
   * @return A left projected either
   */
  public static <L, R, T extends L> Either<L, R> left(final T value) {
    return new Left<>((L) value);
  }

  /**
   * Returns a right projected either.
   *
   * @param value the value to wrap
   * @param <L> the type of the left parameter of the result
   * @param <R> the type of the right parameter of the result
   * @param <T> a refinement type that allows us to wrap subtypes of R
   * @return a right projected either.
   */
  public static <L, R, T extends R> Either<L, R> right(final T value) {
    return new Right<>((R) value);
  }

  /**
   * An error that is thrown if an attempt is made to project an Either to {@link
   * com.swoval.functional.Either.Left} when the object is actually an instance of {@link
   * com.swoval.functional.Either.Right}.
   */
  public static class NotLeftException extends RuntimeException {}

  /**
   * An error that is thrown if an attempt is made to project an Either to {@link
   * com.swoval.functional.Either.Right} when the object is actually an instance of {@link
   * com.swoval.functional.Either.Left}.
   */
  public static class NotRightException extends RuntimeException {}

  /**
   * A left projected {@link com.swoval.functional.Either}.
   *
   * @param <L> the left type
   * @param <R> the right type
   */
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

  /**
   * A right projected {@link com.swoval.functional.Either}.
   *
   * @param <L> the left type
   * @param <R> the right type
   */
  public static final class Right<L, R> extends Either<L, R> {
    private final R value;

    Right(final R value) {
      this.value = value;
    }

    /**
     * Returns the wrapped value.
     *
     * @return the wrapped value.
     */
    public R getValue() {
      return value;
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
