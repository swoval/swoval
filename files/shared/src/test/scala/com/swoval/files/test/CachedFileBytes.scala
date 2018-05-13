package com.swoval.files.test

import java.nio.file.{ Files, Path => JPath }

import com.swoval.files.Directory.Entry

trait LastModified {
  val lastModified: Long
}
case class CachedFileBytes(path: JPath) extends LastModified {
  val lastModified = Files.getLastModifiedTime(path).toMillis
  val bytes: Array[Byte] = if (!Files.isDirectory(path)) Files.readAllBytes(path) else Array.empty
}
object CachedFileBytes {
  def cacheEntry(p: JPath): Entry[CachedFileBytes] = new Entry(p, CachedFileBytes(p))
}
