package com.swoval.files;

import static java.util.Map.Entry;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
      final List<FileTreeDataViews.Entry<T>> oldEntries,
      final List<FileTreeDataViews.Entry<T>> newEntries,
      final CacheObserver<T> cacheObserver) {
    final Map<Path, FileTreeDataViews.Entry<T>> oldMap = new HashMap<>();
    final Iterator<FileTreeDataViews.Entry<T>> oldIterator = oldEntries.iterator();
    while (oldIterator.hasNext()) {
      final FileTreeDataViews.Entry<T> entry = oldIterator.next();
      oldMap.put(entry.getTypedPath().getPath(), entry);
    }
    final Map<Path, FileTreeDataViews.Entry<T>> newMap = new HashMap<>();
    final Iterator<FileTreeDataViews.Entry<T>> newIterator = newEntries.iterator();
    while (newIterator.hasNext()) {
      final FileTreeDataViews.Entry<T> entry = newIterator.next();
      newMap.put(entry.getTypedPath().getPath(), entry);
    }
    diffDirectoryEntries(oldMap, newMap, cacheObserver);
  }

  static <K, V> void diffDirectoryEntries(
      final Map<K, FileTreeDataViews.Entry<V>> oldMap,
      final Map<K, FileTreeDataViews.Entry<V>> newMap,
      final CacheObserver<V> cacheObserver) {
    final Iterator<Entry<K, FileTreeDataViews.Entry<V>>> newIterator =
        new ArrayList<>(newMap.entrySet()).iterator();
    final Iterator<Entry<K, FileTreeDataViews.Entry<V>>> oldIterator =
        new ArrayList<>(oldMap.entrySet()).iterator();
    while (newIterator.hasNext()) {
      final Entry<K, FileTreeDataViews.Entry<V>> entry = newIterator.next();
      final FileTreeDataViews.Entry<V> oldValue = oldMap.get(entry.getKey());
      if (oldValue != null) {
        cacheObserver.onUpdate(oldValue, entry.getValue());
      } else {
        cacheObserver.onCreate(entry.getValue());
      }
    }
    while (oldIterator.hasNext()) {
      final Entry<K, FileTreeDataViews.Entry<V>> entry = oldIterator.next();
      if (!newMap.containsKey(entry.getKey())) {
        cacheObserver.onDelete(entry.getValue());
      }
    }
  }
}
