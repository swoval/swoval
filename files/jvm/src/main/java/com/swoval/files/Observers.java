package com.swoval.files;

import com.swoval.files.Directory.Entry;
import com.swoval.files.Directory.Observer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Container class that wraps multiple {@link com.swoval.files.Directory.Observer} and runs the
 * callbacks for each whenever the {@link com.swoval.files.FileCache} detects an event.
 *
 * @param <T> the data type for the {@link com.swoval.files.FileCache} to which the observers
 *     correspond
 */
class Observers<T> implements Observer<T>, AutoCloseable {
  private final AtomicInteger counter = new AtomicInteger(0);
  private final Map<Integer, Observer<T>> observers = new HashMap<>();

  @Override
  public void onCreate(final Entry<T> newEntry) {
    final List<Observer<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onCreate(newEntry);
  }

  @Override
  public void onDelete(final Entry<T> oldEntry) {
    final List<Observer<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onDelete(oldEntry);
  }

  @Override
  public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
    final List<Observer<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onUpdate(oldEntry, newEntry);
  }

  @Override
  public void onError(Path path, IOException exception) {
    final List<Observer<T>> cbs;
    synchronized (observers) {
      cbs = new ArrayList<>(observers.values());
    }
    final Iterator<Observer<T>> it = cbs.iterator();
    while (it.hasNext()) it.next().onError(path, exception);
  }

  /**
   * Add an observer to receive events.
   *
   * @param observer the new observer
   * @return a handle to the added observer that can be used to halt observation using {@link
   *     com.swoval.files.Observers#removeObserver(int)} .
   */
  public int addObserver(Observer<T> observer) {
    final int key = counter.getAndIncrement();
    synchronized (observers) {
      observers.put(key, observer);
    }
    return key;
  }

  /**
   * Remove an instance of {@link com.swoval.files.Directory.Observer} that was previously added
   * using {@link com.swoval.files.Observers#addObserver(Observer)}.
   *
   * @param handle the handle to remove
   */
  public void removeObserver(int handle) {
    synchronized (observers) {
      observers.remove(handle);
    }
  }

  @Override
  public void close() {
    observers.clear();
  }
}
