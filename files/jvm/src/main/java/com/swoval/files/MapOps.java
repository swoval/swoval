package com.swoval.files;

import static java.util.Map.Entry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides a utility method for diffing two maps of directory entries. It is not in {@link
 * Directory} because of a name class with java.util.Map.Entry and com.swoval.files.Directory.Entry
 * that breaks code-gen.
 */
class MapOps {
  static <T> void diffDirectoryEntries(
      final List<Directory.Entry<T>> oldEntries,
      final List<Directory.Entry<T>> newEntries,
      final Directory.Observer<T> observer) {
    final Map<Path, Directory.Entry<T>> oldMap = new HashMap<>();
    final Iterator<Directory.Entry<T>> oldIterator = oldEntries.iterator();
    while (oldIterator.hasNext()) {
      final Directory.Entry<T> entry = oldIterator.next();
      oldMap.put(entry.getPath(), entry);
    }
    final Map<Path, Directory.Entry<T>> newMap = new HashMap<>();
    final Iterator<Directory.Entry<T>> newIterator = newEntries.iterator();
    while (newIterator.hasNext()) {
      final Directory.Entry<T> entry = newIterator.next();
      newMap.put(entry.getPath(), entry);
    }
    diffDirectoryEntries(oldMap, newMap, observer);
  }

  static <K, V> void diffDirectoryEntries(
      final Map<K, Directory.Entry<V>> oldMap,
      final Map<K, Directory.Entry<V>> newMap,
      final Directory.Observer<V> observer) {
    final Iterator<Entry<K, Directory.Entry<V>>> newIterator =
        new ArrayList<>(newMap.entrySet()).iterator();
    final Iterator<Entry<K, Directory.Entry<V>>> oldIterator =
        new ArrayList<>(oldMap.entrySet()).iterator();
    while (newIterator.hasNext()) {
      final Entry<K, Directory.Entry<V>> entry = newIterator.next();
      final Directory.Entry<V> oldValue = oldMap.get(entry.getKey());
      if (oldValue != null) {
        observer.onUpdate(oldValue, entry.getValue());
      } else {
        observer.onCreate(entry.getValue());
      }
    }
    while (oldIterator.hasNext()) {
      final Entry<K, Directory.Entry<V>> entry = oldIterator.next();
      if (!newMap.containsKey(entry.getKey())) {
        observer.onDelete(entry.getValue());
      }
    }
  }
}
