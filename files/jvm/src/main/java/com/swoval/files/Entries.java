package com.swoval.files;

import static com.swoval.files.LinkOption.NOFOLLOW_LINKS;
import static com.swoval.functional.Either.leftProjection;

import com.swoval.files.DataViews.Converter;
import com.swoval.files.DataViews.Entry;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Provides static constants and methods related to {@link Entry}. */
final class Entries {
  static final int DIRECTORY = 1;
  static final int FILE = 2;
  static final int LINK = 4;
  static final int UNKNOWN = 8;
  static final int NONEXISTENT = 16;

  private Entries() {}

  static <T> Entry<T> get(
      final TypedPath typedPath, final Converter<T> converter, final TypedPath converterPath) {
    try {
      return new ValidEntry<>(typedPath, converter.apply(converterPath));
    } catch (final IOException e) {
      return new InvalidEntry<>(typedPath, e);
    }
  }

  static <T> Entry<T> setExists(final Entry<T> entry, final boolean exists) {
    final int kind =
        (exists ? 0 : NONEXISTENT)
            | (entry.isFile() ? FILE : 0)
            | (entry.isDirectory() ? DIRECTORY : 0)
            | (entry.isSymbolicLink() ? LINK : 0);
    final TypedPath typedPath = TypedPaths.get(entry.getPath(), kind);
    if (entry.getValue().isLeft()) {
      return new InvalidEntry<>(typedPath, Either.leftProjection(entry.getValue()).getValue());
    } else {
      return new ValidEntry<>(typedPath, entry.getValue().get());
    }
  }

  static <T> Entry<T> resolve(final Path path, final Entry<T> entry) {
    final Either<IOException, T> value = entry.getValue();
    final int kind = getKind(entry);
    final TypedPath typedPath = TypedPaths.get(path.resolve(entry.getPath()), kind);
    return value.isRight()
        ? new ValidEntry<>(typedPath, value.get())
        : new InvalidEntry<T>(typedPath, leftProjection(value).getValue());
  }

  private static int getKindFromAttrs(final Path path, final BasicFileAttributes attrs) {
    return attrs.isSymbolicLink()
        ? LINK | (Files.isDirectory(path) ? DIRECTORY : FILE)
        : attrs.isDirectory() ? DIRECTORY : FILE;
  }
  /**
   * Compute the underlying file type for the path.
   *
   * @param path The path whose type is to be determined.
   * @throws IOException if the path can't be opened
   * @return The file type of the path
   */
  static int getKind(final Path path) throws IOException {
    final BasicFileAttributes attrs = NioWrappers.readAttributes(path, NOFOLLOW_LINKS);
    return getKindFromAttrs(path, attrs);
  }

  private static int getKind(final Entry<?> entry) {
    return (entry.isSymbolicLink() ? LINK : 0)
        | (entry.isDirectory() ? DIRECTORY : 0)
        | (entry.isFile() ? FILE : 0);
  }

  private abstract static class EntryImpl<T> implements Entry<T> {
    private final TypedPath typedPath;

    EntryImpl(final TypedPath typedPath) {
      this.typedPath = typedPath;
    }

    @Override
    public boolean exists() {
      return typedPath.exists();
    }

    @Override
    public boolean isDirectory() {
      return typedPath.isDirectory();
    }

    @Override
    public boolean isFile() {
      return typedPath.isFile();
    }

    @Override
    public boolean isSymbolicLink() {
      return typedPath.isSymbolicLink();
    }

    @Override
    public Path getPath() {
      return typedPath.getPath();
    }

    @Override
    public Path toRealPath() {
      return typedPath.toRealPath();
    }

    @Override
    public int hashCode() {
      final int value = com.swoval.functional.Either.getOrElse(getValue(), 0).hashCode();
      return typedPath.hashCode() ^ value;
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof Entry<?>
          && ((Entry<?>) other).getPath().equals(getPath())
          && getValue().equals(((Entry<?>) other).getValue());
    }

    @Override
    public int compareTo(TypedPath that) {
      return this.getPath().compareTo(that.getPath());
    }
  }

  private static final class ValidEntry<T> extends EntryImpl<T> {
    private final T value;

    @Override
    public Either<IOException, T> getValue() {
      return Either.right(value);
    }
    /**
     * Create a new Entry
     *
     * @param typedPath The path to which this entry corresponds
     * @param value The {@code path} derived value for this entry
     */
    ValidEntry(final TypedPath typedPath, final T value) {
      super(typedPath);
      this.value = value;
    }

    @Override
    public String toString() {
      return "ValidEntry(" + getPath() + ", " + value + ")";
    }
  }

  private static class InvalidEntry<T> extends EntryImpl<T> {
    private final IOException exception;

    InvalidEntry(final TypedPath typedPath, final IOException exception) {
      super(typedPath);
      this.exception = exception;
    }

    @Override
    public Either<IOException, T> getValue() {
      return Either.left(exception);
    }

    @Override
    public String toString() {
      return "InvalidEntry(" + getPath() + ", " + exception + ")";
    }
  }
}
