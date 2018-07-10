package com.swoval.files;

interface TotalFunction<T, R> extends Function<T, R> {
  R apply(final T t);
}
