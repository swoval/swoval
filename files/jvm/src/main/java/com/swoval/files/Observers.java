package com.swoval.files;

import com.swoval.files.Directory.Entry;
import com.swoval.files.Directory.Observer;
import com.swoval.files.Directory.OnChange;
import com.swoval.files.Directory.OnError;
import com.swoval.files.Directory.OnUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Container class that wraps multiple {@link Observer} and runs the callbacks for each whenever the
 * {@link FileCache} detects an event.
 * @param <T> The data type for the {@link FileCache} to which the observers correspond
 */
class Observers<T> implements Observer<T>, AutoCloseable {
  private final AtomicInteger counter = new AtomicInteger(0);
  private final Object lock = new Object();
  private final Map<Integer, Observer<T>> observers = new HashMap<>();

  @Override
  public void onCreate(final Entry<T> newEntry) {
    final Collection<Observer<T>> cbs;
    synchronized (lock) {
      cbs = observers.values();
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onCreate(newEntry);
  }

  @Override
  public void onDelete(final Entry<T> oldEntry) {
    final Collection<Observer<T>> cbs;
    synchronized (lock) {
      cbs = observers.values();
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onDelete(oldEntry);
  }

  @Override
  public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
    final Collection<Observer<T>> cbs;
    synchronized (lock) {
      cbs = observers.values();
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onUpdate(oldEntry, newEntry);
  }

  @Override
  public void onError(Path path, IOException exception) {
    final Collection<Observer<T>> cbs;
    synchronized (lock) {
      cbs = observers.values();
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onError(path, exception);
  }

  public int addObserver(Observer<T> observer) {
    final int key = counter.getAndIncrement();
    synchronized (lock) {
      observers.put(key, observer);
    }
    return key;
  }

  public void removeObserver(int handle) {
    synchronized (lock) {
      observers.remove(handle);
    }
  }

  @Override
  public void close() {
    observers.clear();
  }

  /**
   * Simple observer that fires the same callback for all regular events and ignores any errors.
   * @param onchange The callback to fire when a file is created/updated/deleted
   * @param <T> The generic type of the {@link Directory.Entry}
   * @return An {@link Observer} instance
   */
  public static <T> Observer<T> apply(final OnChange<T> onchange) {
    return new Observer<T>() {
      @Override
      public void onCreate(final Entry<T> newEntry) {
        onchange.apply(newEntry);
      }

      @Override
      public void onDelete(final Entry<T> oldEntry) {
        onchange.apply(oldEntry);
      }

      @Override
      public void onUpdate(final Entry<T> oldEntry, Entry<T> newEntry) {
        onchange.apply(newEntry);
      }

      @Override
      public void onError(final Path path, final IOException e) {
      }
    };
  }

  public static <T> Observer<T> apply(
      final OnChange<T> oncreate, final OnUpdate<T> onupdate, final OnChange<T> ondelete, final OnError onerror) {
    return new Observer<T>() {
      @Override
      public void onCreate(final Entry<T> newEntry) {
        oncreate.apply(newEntry);
      }

      @Override
      public void onDelete(final Entry<T> oldEntry) {
        ondelete.apply(oldEntry);
      }

      @Override
      public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
        onupdate.apply(oldEntry, newEntry);
      }

      @Override
      public void onError(final Path path, final IOException ex) {
        onerror.apply(path, ex);
      }
    };
  }
}
