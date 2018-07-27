package com.swoval.files;

import com.swoval.functional.Either;
import java.io.IOException;

/** Provides functional interfaces for processing and managing instances of {@link FileTreeDataView}. */
public class FileTreeDataViews {
  private FileTreeDataViews() {}

  /**
   * Container class for {@link CachedDirectoryImpl} entries. Contains both the path to which the
   * path corresponds along with a data value.
   *
   * @param <T> The value wrapped in the Entry
   */
  public interface Entry<T> extends TypedPath {
    /**
     * Return the value associated with this entry.
     *jjj
     * @return the value associated with this entry.
     */
    Either<IOException, T> getValue();
  }

  /**
   * Converts a Path into an arbitrary value to be cached.
   *
   * @param <R> the generic type generated from the path.
   */
  public interface Converter<R> {

    /**
     * Convert the path to a value.
     *
     * @param path the path to convert
     * @return the converted value
     * @throws IOException when the value can't be computed
     */
    R apply(TypedPath path) throws IOException;
  }

  /**
   * A callback to fire when an error is encountered. This will generally be a {@link
   * java.nio.file.FileSystemLoopException}.
   */
  public interface OnError {

    /**
     * Apply callback for error.
     *
     * @param exception the encountered error
     */
    void apply(final IOException exception);
  }
}
