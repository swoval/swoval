package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.CacheObserver;
import com.swoval.files.FileTreeViews.Observer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class CacheObservers<T> implements FileTreeViews.CacheObserver<T>, AutoCloseable {
  private final AtomicInteger counter = new AtomicInteger(0);
  private final Map<Integer, CacheObserver<T>> observers = new LinkedHashMap<>();

  @Override
  public void onCreate(final Entry<T> newEntry) {
    final List<CacheObserver<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<CacheObserver<T>> it = cbs.iterator();
    while (it.hasNext()) {
      try {
        it.next().onCreate(newEntry);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void onDelete(final Entry<T> oldEntry) {
    final List<FileTreeViews.CacheObserver<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<FileTreeViews.CacheObserver<T>> it = cbs.iterator();
    while (it.hasNext()) {
      try {
        it.next().onDelete(oldEntry);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
    final List<FileTreeViews.CacheObserver<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<FileTreeViews.CacheObserver<T>> it = cbs.iterator();
    while (it.hasNext()) {
      try {
        it.next().onUpdate(oldEntry, newEntry);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void onError(IOException exception) {
    final List<FileTreeViews.CacheObserver<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<FileTreeViews.CacheObserver<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onError(exception);
  }

  /**
   * Add an cacheObserver to receive events.
   *
   * @param observer the new cacheObserver
   * @return a handle to the added cacheObserver that can be used to halt observation using {@link
   *     com.swoval.files.Observers#removeObserver(int)} .
   */
  int addObserver(final Observer<Entry<T>> observer) {
    final int key = counter.getAndIncrement();
    synchronized (observers) {
      observers.put(key, CacheObservers.fromObserver(observer));
    }
    return key;
  }

  int addCacheObserver(final FileTreeViews.CacheObserver<T> cacheObserver) {
    final int key = counter.getAndIncrement();
    synchronized (observers) {
      observers.put(key, cacheObserver);
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

  static <T> CacheObserver<T> fromObserver(final Observer<Entry<T>> observer) {
    return new CacheObserver<T>() {
      @Override
      public void onCreate(final Entry<T> newEntry) {
        observer.onNext(newEntry);
      }

      @Override
      public void onDelete(final Entry<T> oldEntry) {
        observer.onNext(oldEntry);
      }

      @Override
      public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
        observer.onNext(newEntry);
      }

      @Override
      public void onError(final IOException exception) {
        observer.onError(exception);
      }
    };
  }
}
