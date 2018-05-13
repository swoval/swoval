package com.swoval.files

import java.io.File
import java.nio.file.{ Path => JPath }

import com.swoval.files.Directory.Entry
import com.swoval.files.test._
import com.swoval.files.test.FileBytes
import utest._

object EntryFilterTest extends TestSuite {
  implicit class PathFilterOps[T](val pathFilter: EntryFilter[T]) extends AnyVal {
    def &&(other: EntryFilter[_ >: T]): EntryFilter[T] = EntryFilters.AND[T](pathFilter, other)
  }
  override val tests = Tests {
    'simple - withTempFileSync { f =>
      val filter: EntryFilter[JPath] = EntryFilters.fromFileFilter((_: File) == f.toFile)
      assert(filter.accept(new Entry(f, f)))
    }
    'combined - withTempFileSync { f =>
      f.write("foo")
      val bytes = "foo".getBytes
      val lastModified = 2000
      f.setLastModifiedTime(lastModified)
      val base
        : EntryFilter[LastModified] = (_: Entry[LastModified]).value.lastModified == lastModified
      val hashFilter: EntryFilter[FileBytes] =
        (_: Entry[FileBytes]).value.bytes.sameElements(bytes)
      val filter = hashFilter && base
      assert(filter.accept(new Entry(f, FileBytes(f))))
      f.write("bar")
      assert(!filter.accept(new Entry(f, FileBytes(f))))
      compileError("base && hashFilter") // hashFilter is not a subtype of base
      ()
    }
  }
}
