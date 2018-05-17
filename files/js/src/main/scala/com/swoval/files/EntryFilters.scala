package com.swoval.files

import com.swoval.files.Directory.Entry
import com.swoval.files.Directory.EntryFilter
import java.io.FileFilter

private[files] object EntryFilters {

  var AllPass: EntryFilter[Any] = new EntryFilter[Any]() {
    override def accept(entry: Entry[_]): Boolean = true

    override def toString(): String = "AllPass"
  }

  def AND[T](left: EntryFilter[T], right: EntryFilter[_ >: T]): EntryFilter[T] =
    new CombinedFilter(left, right)

  def fromFileFilter[T](f: FileFilter): EntryFilter[T] = new EntryFilter[T]() {
    override def accept(entry: Entry[_ <: T]): Boolean =
      f.accept(entry.path.toFile())

    override def toString(): String = "FromFileFilter(" + f + ")"
  }

  class CombinedFilter[T <: T0, T0](private val left: EntryFilter[T],
                                    private val right: EntryFilter[T0])
      extends EntryFilter[T] {

    override def accept(entry: Entry[_ <: T]): Boolean =
      left.accept(entry) && right.accept(entry)

  }

}
