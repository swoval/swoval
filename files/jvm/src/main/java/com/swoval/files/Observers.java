package com.swoval.files;

import com.swoval.files.FileTreeViews.Observer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Container class that wraps multiple {@link FileTreeViews.Observer} and runs the callbacks for
 * each whenever the {@link PathWatcher} detects an event.
 *
 * @param <T> the data type for the {@link PathWatcher} to which the observers correspond
 */
class Observers<T> implements FileTreeViews.Observer<T>, AutoCloseable {
  private final AtomicInteger counter = new AtomicInteger(0);
  private final Map<Integer, FileTreeViews.Observer<T>> observers = new LinkedHashMap<>();

  @Override
  public void onNext(final T t) {
    final List<FileTreeViews.Observer<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<FileTreeViews.Observer<T>> it = cbs.iterator();
    while (it.hasNext()) {
      try {
        it.next().onNext(t);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void onError(final Throwable throwable) {
    final List<FileTreeViews.Observer<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<FileTreeViews.Observer<T>> it = cbs.iterator();
    while (it.hasNext()) {
      try {
        it.next().onError(throwable);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Add an cacheObserver to receive events.
   *
   * @param observer the new cacheObserver
   * @return a handle to the added cacheObserver that can be used to halt observation using {@link
   *     com.swoval.files.Observers#removeObserver(int)} .
   */
  int addObserver(final Observer<T> observer) {
    final int key = counter.getAndIncrement();
    synchronized (observers) {
      observers.put(key, observer);
    }
    return key;
  }

  /**
   * Remove an instance of {@link FileTreeViews.CacheObserver} that was previously added using
   * {@link com.swoval.files.Observers#addObserver(FileTreeViews.Observer)}.
   *
   * @param handle the handle to remove
   */
  void removeObserver(int handle) {
    synchronized (observers) {
      observers.remove(handle);
    }
  }

  @Override
  public void close() {
    observers.clear();
  }
}
