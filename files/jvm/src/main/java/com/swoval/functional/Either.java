package com.swoval.functional;

/**
 * Represents a value that can be one of two types
 *
 * @param <L> The left value
 * @param <R> The right value
 */
public abstract class Either<L, R> {
  private Either() {}

  public abstract L left();

  public abstract R right();

  public abstract boolean isLeft();

  public abstract boolean isRight();

  @SuppressWarnings("unchecked")
  public <L, R, T extends L> Either<T, R> castLeft(final Class<T> clazz) {
    if (isRight()) return (Either<T, R>) this;
    else if (clazz.isAssignableFrom(left().getClass())) return (Either<T, R>) this;
    else throw new ClassCastException(left() + " is not an instance of " + clazz);
  }

  @SuppressWarnings("unchecked")
  public <L, R, T extends R> Either<L, T> castRight(final Class<T> clazz) {
    if (this.isLeft()) return (Either<L, T>) this;
    else if (clazz.isAssignableFrom(right().getClass())) return (Either<L, T>) this;
    else throw new ClassCastException(right() + " is not an instance of " + clazz);
  }

  public static <L, R, T extends L> Either<L, R> left(final T value) {
    return new Left<>((L) value);
  }

  public static <L, R, T extends R> Either<L, R> right(final T value) {
    return new Right<>((R) value);
  }

  public static class NotLeftException extends RuntimeException {}

  public static class NotRightException extends RuntimeException {}

  private static final class Left<L, R> extends Either<L, R> {
    private final L value;

    public Left(final L value) {
      this.value = value;
    }

    @Override
    public L left() {
      return value;
    }

    @Override
    public R right() {
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
  }

  private static final class Right<L, R> extends Either<L, R> {
    private final R value;

    public Right(final R value) {
      this.value = value;
    }

    @Override
    public L left() {
      throw new NotLeftException();
    }

    @Override
    public R right() {
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
  }
}
