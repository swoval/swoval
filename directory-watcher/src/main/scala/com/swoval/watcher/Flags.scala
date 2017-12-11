package com.swoval.watcher

case class FileEvent(fileName: String, flags: Int, id: Long) extends Flags.Event {
  import Flags.Event._
  private def hasFlags(f: Int) = (flags & f) != 0
  def isModified = hasFlags(ItemInodeMetaMod | ItemModified)
  def isNewFile = !isModified && !isRemoved && !isTouched && hasFlags(ItemCreated)
  def isRemoved = hasFlags(ItemRemoved)
  def isTouched = hasFlags(ItemInodeMetaMod)
  override def toString() = s"FileEvent($fileName, $id, ${Flags.Event.flags(flags)})"
}

object Flags {
  case class Create(value: Int) {
    import Create._
    def setUseCFTypes = new Create(value | UseCFTypes)
    def setNoDefer = new Create(value | NoDefer)
    def setWatchRoot = new Create(value | WatchRoot)
    def setIgnoreSelf = new Create(value | IgnoreSelf)
    def setFileEvents = new Create(value | FileEvents)
    def setMarkSelf = new Create(value | MarkSelf)
    def useExtendedData = new Create(value | UseExtendedData)
  }

  object Create extends Create(0x00000002) {
    // https://developer.apple.com/documentation/coreservices/1455376-fseventstreamcreateflags?language=objc
    val None = 0
    val UseCFTypes = 0x00000001
    val NoDefer = 0x00000002
    val WatchRoot = 0x00000004
    val IgnoreSelf = 0x00000008
    val FileEvents = 0x00000010
    val MarkSelf = 0x00000020
    val UseExtendedData = 0x00000040
  }

  trait Event extends Any {
    //https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags
    import Event._
    def flags: Int
    def isNone = flags == 0
    def mustScanSubDirs = (flags & MustScanSubDirs) != 0
    def userDropped = (flags & UserDropped) != 0
    def kernelDropped = (flags & KernelDropped) != 0
    def eventIdsWrapped = (flags & EventIdsWrapped) != 0
    def historyDone = (flags & HistoryDone) != 0
    def rootChanged = (flags & RootChanged) != 0
    def mount = (flags & Mount) != 0
    def unmount = (flags & Unmount) != 0
    def itemChangeOwner = (flags & ItemChangeOwner) != 0
    def itemCreated = (flags & ItemCreated) != 0
    def itemFinderInfoMod = (flags & ItemFinderInfoMod) != 0
    def itemInodeMetaMod = (flags & ItemInodeMetaMod) != 0
    def itemIsDir = (flags & ItemIsDir) != 0
    def itemIsFile = (flags & ItemIsFile) != 0
    def itemIsHardlink = (flags & ItemIsHardlink) != 0
    def itemIsLastHardlink = (flags & ItemIsLastHardlink) != 0
    def itemIsSymlink = (flags & ItemIsSymlink) != 0
    def itemModified = (flags & ItemModified) != 0
    def itemRemoved = (flags & ItemRemoved) != 0
    def itemRenamed = (flags & ItemRenamed) != 0
    def itemXattrMod = (flags & ItemXattrMod) != 0
    def ownEvent = (flags & OwnEvent) != 0
    def itemCloned = (flags & ItemCloned) != 0
  }
  object Event {
    implicit class Impl(val flags: Int) extends AnyVal with Event
    def apply(i: Int) = new Impl(i)
    def flags(flag: Event) = {
      import flag._
      val values = Seq(
        s"  mustScanSubDirs: ${mustScanSubDirs}",
        s"  userDropped: ${userDropped}",
        s"  kernelDropped: ${kernelDropped}",
        s"  eventIdsWrapped: ${eventIdsWrapped}",
        s"  historyDone: ${historyDone}",
        s"  rootChanged: ${rootChanged}",
        s"  mount: ${mount}",
        s"  unmount: ${unmount}",
        s"  itemChangeOwner: ${itemChangeOwner}",
        s"  itemCreated: ${itemCreated}",
        s"  itemFinderInfoMod: ${itemFinderInfoMod}",
        s"  itemInodeMetaMod: ${itemInodeMetaMod}",
        s"  itemIsDir: ${itemIsDir}",
        s"  itemIsFile: ${itemIsFile}",
        s"  itemIsHardlink: ${itemIsHardlink}",
        s"  itemIsLastHardlink: ${itemIsLastHardlink}",
        s"  itemIsSymlink: ${itemIsSymlink}",
        s"  itemModified: ${itemModified}",
        s"  itemRemoved: ${itemRemoved}",
        s"  itemRenamed: ${itemRenamed}",
        s"  itemXattrMod: ${itemXattrMod}",
        s"  ownEvent: ${ownEvent}",
        s"  itemCloned: ${itemCloned}",
      )
      s"EventStreamFlags(\n${values mkString "\n"}\n)"
    }
    val None = 0
    val MustScanSubDirs = 0x00000001
    val UserDropped = 0x00000002
    val KernelDropped = 0x00000004
    val EventIdsWrapped = 0x00000008
    val HistoryDone = 0x00000010
    val RootChanged = 0x00000020
    val Mount = 0x00000040
    val Unmount = 0x00000080
    val ItemChangeOwner = 0x00004000
    val ItemCreated = 0x00000100
    val ItemFinderInfoMod = 0x00002000
    val ItemInodeMetaMod = 0x00000400
    val ItemIsDir = 0x00020000
    val ItemIsFile = 0x00010000
    val ItemIsHardlink = 0x00100000
    val ItemIsLastHardlink = 0x00200000
    val ItemIsSymlink = 0x00040000
    val ItemModified = 0x00001000
    val ItemRemoved = 0x00000200
    val ItemRenamed = 0x00000800
    val ItemXattrMod = 0x00008000
    val OwnEvent = 0x00080000
    val ItemCloned = 0x00400000
  }
}
