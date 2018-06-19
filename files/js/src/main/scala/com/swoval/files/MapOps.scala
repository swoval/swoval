package com.swoval.files

import java.util.ArrayList
import java.util.Iterator
import java.util.Map
import java.util.Map.Entry

object MapOps {

  def diffDirectoryEntries[K, V](oldMap: Map[K, Directory.Entry[V]],
                                 newMap: Map[K, Directory.Entry[V]],
                                 observer: Directory.Observer[V]): Unit = {
    val newIterator: Iterator[Entry[K, Directory.Entry[V]]] =
      new ArrayList(newMap.entrySet()).iterator()
    val oldIterator: Iterator[Entry[K, Directory.Entry[V]]] =
      new ArrayList(oldMap.entrySet()).iterator()
    while (newIterator.hasNext) {
      val entry: Entry[K, Directory.Entry[V]] = newIterator.next()
      val oldValue: Directory.Entry[V] = oldMap.get(entry.getKey)
      if (oldValue != null) {
        observer.onUpdate(oldValue, entry.getValue)
      } else {
        observer.onCreate(entry.getValue)
      }
    }
    while (oldIterator.hasNext) {
      val entry: Entry[K, Directory.Entry[V]] = oldIterator.next()
      if (!newMap.containsKey(entry.getKey)) {
        observer.onDelete(entry.getValue)
      }
    }
  }

}
