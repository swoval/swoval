package com.swoval.files;

import static java.util.Map.Entry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides a utility method for diffing two maps of directory entries. It is not in {@link
 * CachedDirectoryImpl} because of a name class with java.util.Map.Entry and
 * com.swoval.files.CachedDirectory.Entry that breaks code-gen.
 */
class MapOps {
  private MapOps() {}

  static <T> void diffDirectoryEntries(
      final List<DataViews.Entry<T>> oldEntries,
      final List<DataViews.Entry<T>> newEntries,
      final FileTreeViews.CacheObserver<T> cacheObserver) {
    final Map<Path, DataViews.Entry<T>> oldMap = new TreeMap<>();
    final Iterator<DataViews.Entry<T>> oldIterator = oldEntries.iterator();
    while (oldIterator.hasNext()) {
      final DataViews.Entry<T> entry = oldIterator.next();
      oldMap.put(entry.getPath(), entry);
    }
    final Map<Path, DataViews.Entry<T>> newMap = new TreeMap<>();
    final Iterator<DataViews.Entry<T>> newIterator = newEntries.iterator();
    while (newIterator.hasNext()) {
      final DataViews.Entry<T> entry = newIterator.next();
      newMap.put(entry.getPath(), entry);
    }
    diffDirectoryEntries(oldMap, newMap, cacheObserver);
  }

  static <K, V> void diffDirectoryEntries(
      final Map<K, DataViews.Entry<V>> oldMap,
      final Map<K, DataViews.Entry<V>> newMap,
      final FileTreeViews.CacheObserver<V> cacheObserver) {
    final Iterator<Entry<K, DataViews.Entry<V>>> newIterator =
        new ArrayList<>(newMap.entrySet()).iterator();
    final Iterator<Entry<K, DataViews.Entry<V>>> oldIterator =
        new ArrayList<>(oldMap.entrySet()).iterator();
    while (newIterator.hasNext()) {
      final Entry<K, DataViews.Entry<V>> entry = newIterator.next();
      final DataViews.Entry<V> oldValue = oldMap.get(entry.getKey());
      if (oldValue != null) {
        cacheObserver.onUpdate(oldValue, entry.getValue());
      } else {
        cacheObserver.onCreate(entry.getValue());
      }
    }
    while (oldIterator.hasNext()) {
      final Entry<K, DataViews.Entry<V>> entry = oldIterator.next();
      if (!newMap.containsKey(entry.getKey())) {
        cacheObserver.onDelete(entry.getValue());
      }
    }
    newMap.clear();
    oldMap.clear();
  }
}
