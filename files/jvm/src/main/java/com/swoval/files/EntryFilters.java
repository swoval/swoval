package com.swoval.files;

import com.swoval.files.Directory.Entry;
import com.swoval.files.Directory.EntryFilter;
import java.io.FileFilter;

class EntryFilters {
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
