package com.swoval.files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

class Lockable {
  private final ReentrantLock reentrantLock;

  Lockable(final ReentrantLock reentrantLock) {
    this.reentrantLock = reentrantLock;
  }

  boolean lock() {
    try {
      return reentrantLock.tryLock(1, TimeUnit.MINUTES);
    } catch (final InterruptedException e) {
      return false;
    }
  }

  void unlock() {
    reentrantLock.unlock();
  }
}

class LockableMap<K, V extends AutoCloseable> extends Lockable {
  private final Map<K, V> map;

  LockableMap(final Map<K, V> map, final ReentrantLock reentrantLock) {
    super(reentrantLock);
    this.map = map;
  }

  LockableMap() {
    this(new HashMap<K, V>(), new ReentrantLock());
  }

  @SuppressWarnings("EmptyCatchBlock")
  void clear() {
    if (lock()) {
      try {
        final Iterator<V> values = new ArrayList<>(map.values()).iterator();
        while (values.hasNext()) {
          try {
            final V v = values.next();
            v.close();
          } catch (final Exception e) {
          }
        }
        map.clear();
      } finally {
        unlock();
      }
    }
  }

  Iterator<Entry<K, V>> iterator() {
    if (lock()) {
      try {
        return new ArrayList<>(map.entrySet()).iterator();
      } finally {
        unlock();
      }
    } else {
      return Collections.emptyListIterator();
    }
  }

  List<K> keys() {
    if (lock()) {
      try {
        return new ArrayList<>(map.keySet());
      } finally {
        unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }

  List<V> values() {
    if (lock()) {
      try {
        return new ArrayList<>(map.values());
      } finally {
        unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }

  V get(final K key) {
    if (lock()) {
      try {
        return map.get(key);
      } finally {
        unlock();
      }
    } else {
      return (V) null;
    }
  }

  V put(final K key, V value) {
    if (lock()) {
      try {
        return map.put(key, value);
      } finally {
        unlock();
      }
    } else {
      return (V) null;
    }
  }

  V remove(final K key) {
    if (lock()) {
      try {
        return map.remove(key);
      } finally {
        unlock();
      }
    } else {
      return (V) null;
    }
  }
}
