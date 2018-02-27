package com.swoval.files.apple

import com.swoval.files.apple.Flags.Event._

//remove if not needed
import scala.collection.JavaConversions._

class FileEvent(val fileName: String, var flags: Int)
    extends com.swoval.files.apple.Event {

  private def hasFlags(f: Int): Boolean = (flags & f) != 0

  def isModified(): Boolean = hasFlags(ItemInodeMetaMod | ItemModified)

  def isNewFile(): Boolean =
    isCreated && !isModified && !isRemoved && !isTouched

  def isCreated(): Boolean = hasFlags(ItemCreated)

  def isRemoved(): Boolean = hasFlags(ItemRemoved)

  def isTouched(): Boolean = hasFlags(ItemInodeMetaMod)

  override def toString(): String =
    "FileEvent(" + fileName + ", " + Flags.Event.flags(this) +
      ")"

}
