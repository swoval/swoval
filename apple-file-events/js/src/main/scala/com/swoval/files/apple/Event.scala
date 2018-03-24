package com.swoval.files.apple

//remove if not needed
import scala.collection.JavaConversions._

abstract class Event {

//https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags
  def flags(): Int

  def isNone(): Boolean = flags() == 0

  def mustScanSubDirs(): Boolean = (flags() & Flags.Event.MustScanSubDirs) != 0

  def userDropped(): Boolean = (flags() & Flags.Event.UserDropped) != 0

  def kernelDropped(): Boolean = (flags() & Flags.Event.KernelDropped) != 0

  def eventIdsWrapped(): Boolean = (flags() & Flags.Event.EventIdsWrapped) != 0

  def historyDone(): Boolean = (flags() & Flags.Event.HistoryDone) != 0

  def rootChanged(): Boolean = (flags() & Flags.Event.RootChanged) != 0

  def mount(): Boolean = (flags() & Flags.Event.Mount) != 0

  def unmount(): Boolean = (flags() & Flags.Event.Unmount) != 0

  def itemChangeOwner(): Boolean = (flags() & Flags.Event.ItemChangeOwner) != 0

  def itemCreated(): Boolean = (flags() & Flags.Event.ItemCreated) != 0

  def itemFinderInfoMod(): Boolean =
    (flags() & Flags.Event.ItemFinderInfoMod) != 0

  def itemInodeMetaMod(): Boolean =
    (flags() & Flags.Event.ItemInodeMetaMod) != 0

  def itemIsDir(): Boolean = (flags() & Flags.Event.ItemIsDir) != 0

  def itemIsFile(): Boolean = (flags() & Flags.Event.ItemIsFile) != 0

  def itemIsHardlink(): Boolean = (flags() & Flags.Event.ItemIsHardlink) != 0

  def itemIsLastHardlink(): Boolean =
    (flags() & Flags.Event.ItemIsLastHardlink) != 0

  def itemIsSymlink(): Boolean = (flags() & Flags.Event.ItemIsSymlink) != 0

  def itemModified(): Boolean = (flags() & Flags.Event.ItemModified) != 0

  def itemRemoved(): Boolean = (flags() & Flags.Event.ItemRemoved) != 0

  def itemRenamed(): Boolean = (flags() & Flags.Event.ItemRenamed) != 0

  def itemXattrMod(): Boolean = (flags() & Flags.Event.ItemXattrMod) != 0

  def ownEvent(): Boolean = (flags() & Flags.Event.OwnEvent) != 0

  def itemCloned(): Boolean = (flags() & Flags.Event.ItemCloned) != 0

}
