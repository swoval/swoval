package com.swoval.files.apple

import com.swoval.files.apple.Flags.Event.ItemCreated
import com.swoval.files.apple.Flags.Event.ItemInodeMetaMod
import com.swoval.files.apple.Flags.Event.ItemModified
import com.swoval.files.apple.Flags.Event.ItemRemoved

/**
 * Simple wrapper around the event provided by the apple file system event callback.
 *
 * @see [[https://developer.apple.com/documentation/coreservices/fseventstreamcallback FsEventStreamCallback]]
 */
class FileEvent(val fileName: String, flags: Int) extends Event {

  private val _flags: Int = flags

  private def hasFlags(f: Int): Boolean = (_flags & f) != 0

  def isModified(): Boolean = hasFlags(ItemInodeMetaMod | ItemModified)

  def isNewFile(): Boolean =
    isCreated && !isModified && !isRemoved && !isTouched

  def isCreated(): Boolean = hasFlags(ItemCreated)

  def isRemoved(): Boolean = hasFlags(ItemRemoved)

  def isTouched(): Boolean = hasFlags(ItemInodeMetaMod)

  override def toString(): String =
    "FileEvent(" + fileName + ", " + Flags.Event.flags(this) +
      ")"

  override def flags(): Int = _flags

}
