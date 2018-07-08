package com.swoval.files;

import com.swoval.files.Directory.Entry;
import com.swoval.files.Directory.EntryFilter;
import java.io.FileFilter;

/** Static helpers for creating instance of {@link com.swoval.files.Directory.EntryFilter}. */
public class EntryFilters {
  private EntryFilters() {}

  /** Accept any entry with any value type. */
  public static EntryFilter<Object> AllPass =
      new EntryFilter<Object>() {
        @Override
        public boolean accept(final Entry<? extends Object> entry) {
          return true;
        }

        @Override
        public String toString() {
          return "AllPass";
        }
      };

  /**
   * Combine two entry filters
   *
   * @param left The first entry filter to apply
   * @param right The second entry filter to apply if the entry is accepted by the first filter.
   *     This filter must be for an entry whose value is a super class of the left entry filter.
   * @param <T> The greatest lower bound of the two entry filters
   * @return An entry filter that first applies the left filter and then the right filter if the
   *     entry is accepted by the left filter.
   */
  public static <T> EntryFilter<T> AND(
      final EntryFilter<T> left, final EntryFilter<? super T> right) {
    return new CombinedFilter<>(left, right);
  }

  public static <T> EntryFilter<T> fromFileFilter(final FileFilter f) {
    return new EntryFilter<T>() {
      @Override
      public boolean accept(Entry<? extends T> entry) {
        return f.accept(entry.getPath().toFile());
      }

      @Override
      public String toString() {
        return "FromFileFilter(" + f + ")";
      }
    };
  }

  static class CombinedFilter<T extends T0, T0> implements EntryFilter<T> {
    private final EntryFilter<T> left;
    private final EntryFilter<T0> right;

    CombinedFilter(final EntryFilter<T> left, final EntryFilter<T0> right) {
      this.left = left;
      this.right = right;
    }

    @Override
    public boolean accept(Entry<? extends T> entry) {
      return left.accept(entry) && right.accept(entry);
    }
  }
}
