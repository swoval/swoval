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

  public static <L, R> Either<L, R> left(final L value) {
    return new Left<>(value);
  }

  public static <L, R> Either<L, R> right(final R value) {
    return new Right<>(value);
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
  }
}
