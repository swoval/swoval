package com.swoval.files;

import static com.swoval.functional.Either.leftProjection;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Entry;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Provides static constants and methods related to {@link com.swoval.files.Directory.Entry}. */
final class Entries {
  static final int DIRECTORY = 1;
  static final int FILE = 2;
  static final int LINK = 4;
  static final int UNKNOWN = 8;

  private Entries() {}

  static <T> Entry<T> get(
      final Path path, final int kind, final Converter<T> converter, final Path converterPath) {
    try {
      return new ValidEntry<>(path, kind, converter.apply(converterPath));
    } catch (final IOException e) {
      return new InvalidEntry<>(path, kind, e);
    }
  }

  static <T> Entry<T> resolve(final Path path, final Entry<T> entry) {
    final Either<IOException, T> value = entry.getValue();
    final int kind = getKind(entry);
    return value.isRight()
        ? new ValidEntry<>(path.resolve(entry.getPath()), kind, value.get())
        : new InvalidEntry<T>(
            path.resolve(entry.getPath()), kind, leftProjection(value).getValue());
  }

  private static int getKind(final Entry<?> entry) {
    return (entry.isSymbolicLink() ? LINK : 0)
        | (entry.isDirectory() ? DIRECTORY : 0)
        | (entry.isFile() ? FILE : 0);
  }

  /**
   * Compute the underlying file type for the path.
   *
   * @param path The path whose type is to be determined.
   * @param attrs The attributes of the ile
   * @return The file type of the path
   */
  static int getKind(final Path path, final BasicFileAttributes attrs) {
    return attrs.isSymbolicLink()
        ? LINK | (Files.isDirectory(path) ? DIRECTORY : FILE)
        : attrs.isDirectory() ? DIRECTORY : FILE;
  }

  /**
   * Compute the underlying file type for the path.
   *
   * @param path The path whose type is to be determined.
   * @return The file type of the path
   * @throws IOException if the path can't be opened
   */
  static int getKind(final Path path) throws IOException {
    return getKind(path, NioWrappers.readAttributes(path, LinkOption.NOFOLLOW_LINKS));
  }

  private abstract static class EntryImpl<T> implements Entry<T> {
    private final int kind;
    private final Path path;

    EntryImpl(final Path path, final int kind) {
      this.path = path;
      this.kind = kind;
    }

    @Override
    public boolean isDirectory() {
      return is(Entries.DIRECTORY) || (is(Entries.UNKNOWN) && Files.isDirectory(path));
    }

    @Override
    public boolean isFile() {
      return is(Entries.FILE) || (is(Entries.UNKNOWN) && Files.isRegularFile(path));
    }

    @Override
    public boolean isSymbolicLink() {
      return is(Entries.LINK) || (is(Entries.UNKNOWN) && Files.isRegularFile(path));
    }

    @Override
    public Path getPath() {
      return path;
    }

    private boolean is(final int kind) {
      return (kind & this.kind) != 0;
    }

    @Override
    public int hashCode() {
      final int value = com.swoval.functional.Either.getOrElse(getValue(), 0).hashCode();
      return path.hashCode() ^ value;
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof Entry<?>
          && ((Entry<?>) other).getPath().equals(getPath())
          && getValue().equals(((Entry<?>) other).getValue());
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
     * @param path The path to which this entry corresponds blah
     * @param value The {@code path} derived value for this entry
     * @param kind The type of file that this entry represents. In the case of symbolic links, it
     *     can be both a link and a directory or file.
     */
    ValidEntry(final Path path, final int kind, final T value) {
      super(path, kind);
      this.value = value;
    }

    @Override
    public String toString() {
      return "ValidEntry(" + getPath() + ", " + value + ")";
    }
  }

  private static class InvalidEntry<T> extends EntryImpl<T> {
    private final IOException exception;

    InvalidEntry(final Path path, final int kind, final IOException exception) {
      super(path, kind);
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
