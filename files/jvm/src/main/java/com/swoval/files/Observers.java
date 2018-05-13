package com.swoval.files;

import com.swoval.files.Directory.Entry;
import com.swoval.files.FileCache.Observer;
import com.swoval.files.FileCache.OnChange;
import com.swoval.files.FileCache.OnUpdate;
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

  public static <T> Observer<T> apply(final FileCache.OnChange<T> onchange) {
    return new Observer<T>() {
      @Override
      public void onCreate(Entry<T> newEntry) {
        onchange.apply(newEntry);
      }

      @Override
      public void onDelete(Entry<T> oldEntry) {
        onchange.apply(oldEntry);
      }

      @Override
      public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
        onchange.apply(newEntry);
      }
    };
  }

  public static <T> Observer<T> apply(final OnChange<T> onchange, final OnUpdate<T> onupdate) {
    return new Observer<T>() {
      @Override
      public void onCreate(Entry<T> newEntry) {
        onchange.apply(newEntry);
      }

      @Override
      public void onDelete(Entry<T> oldEntry) {
        onchange.apply(oldEntry);
      }

      @Override
      public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
        onupdate.apply(oldEntry, newEntry);
      }
    };
  }

  public static <T> Observer<T> apply(
      final OnChange<T> oncreate, final OnUpdate<T> onupdate, final OnChange<T> ondelete) {
    return new Observer<T>() {
      @Override
      public void onCreate(Entry<T> newEntry) {
        oncreate.apply(newEntry);
      }

      @Override
      public void onDelete(Entry<T> oldEntry) {
        ondelete.apply(oldEntry);
      }

      @Override
      public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
        onupdate.apply(oldEntry, newEntry);
      }
    };
  }
}
