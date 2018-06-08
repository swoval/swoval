package com.swoval.files;

class ArrayOps {
  public interface Filter<T> {
    boolean accept(T t);
  }
  public static <T> boolean contains(final T[] array, final T el) {
    return find(array, el) != null;
  }
  public static <T> T find(final T[] array, final T el) {
    return find(array, new Filter<T>() {
      @Override
      public boolean accept(T t) {
        return el == null ? t == null : t.equals(el);
      }
    });
  }
  public static <T> T find(T[] array, Filter<T> filter) {
    T result = null;
    int i = 0;
    while (result == null && i < array.length) {
      final T el = array[i];
      if (filter.accept(el)) result = el;
      i += 1;
    }
    return result;
  }
}
