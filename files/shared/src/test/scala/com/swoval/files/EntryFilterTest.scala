package com.swoval.files

import java.io.File
import java.nio.file.Path

import com.swoval.files.Directory.{ Entry, EntryFilter }
import com.swoval.files.test.{ FileBytes, _ }
import com.swoval.test._
import utest._

object EntryFilterTest extends TestSuite {
  implicit class EntryFilterOps[T](val pathFilter: EntryFilter[T]) extends AnyVal {
    def &&(other: EntryFilter[_ >: T]): EntryFilter[T] = EntryFilters.AND[T](pathFilter, other)
  }
  override val tests = Tests {
    'simple - withTempFileSync { f =>
      val filter: EntryFilter[Path] = EntryFilters.fromFileFilter((_: File) == f.toFile)
      assert(filter.accept(Entries.get(f, Entries.FILE, identity(_: Path), f)))
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
      assert(filter.accept(Entries.get(f, Entries.FILE, FileBytes(_: Path), f)))
      f.write("bar")
      assert(!filter.accept(Entries.get(f, Entries.FILE, FileBytes(_: Path), f)))
      compileError("base && hashFilter") // hashFilter is not a subtype of base
      ()
    }
  }
}
