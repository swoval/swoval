package com.swoval.files;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import static java.util.Map.Entry;

/**
 * Provides a utility method for diffing two maps of directory entries. It is not in {@link
 * Directory} because of a name class with java.util.Map.Entry and com.swoval.files.Directory.Entry
 * that breaks code-gen.
 */
class MapOps {
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
