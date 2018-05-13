package com.swoval.files.test

import java.nio.file.{ Files, Path => JPath }

import com.swoval.files.Directory.Entry

case class CachedLastModified(path: JPath) {
  val lastModified = Files.getLastModifiedTime(path).toMillis
}
object CachedLastModified {
  def cacheEntry(p: JPath): Entry[CachedLastModified] =
    new Entry(p, CachedLastModified(p))
}
