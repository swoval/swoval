package com.swoval.watchservice

import com.sun.jna.ptr.PointerByReference
import com.sun.jna.{ Pointer => Ptr, _ }

class CFArrayRef extends PointerByReference

class CFRunLoopRef extends PointerByReference

class CFStringRef extends PointerByReference

object CFStringRef {
  def toCFString(s: String): CFStringRef = {
    val chars = s.toCharArray
    CarbonAPI.INSTANCE.CFStringCreateWithCharacters(null, chars, chars.length)
  }
}

class FSEventStreamRef extends PointerByReference

object CarbonAPI {
  val INSTANCE: CarbonAPI = Native.loadLibrary("Carbon", classOf[CarbonAPI])
}

trait FSEventStreamCallback extends Callback {
  private type Ref = FSEventStreamRef
  def invoke(s: Ref, cbInfo: Ptr, count: NativeLong, paths: Ptr, flags: Ptr, ids: Ptr): Unit
}

trait CarbonAPI extends Library {
  def CFArrayCreate(allocator: Ptr, // unused
                    values: Array[Ptr],
                    numValues: Int,
                    callBacks: Void /* unused */ ): CFArrayRef

  def CFStringCreateWithCharacters(allocator: Ptr, //  always pass NULL
                                   chars: Array[Char],
                                   numChars: Int): CFStringRef

  def FSEventStreamCreate(allocator: Ptr, //unused
                          callback: Callback,
                          context: Ptr,
                          pathsToWatch: CFArrayRef,
                          sinceWhen: Long, // kFSEventStreamEventIdSinceNow == 0xFFFFFFFFFFFFFFFFLL
                          latency: Double, // seconds
                          flags: Int): FSEventStreamRef

  def FSEventStreamInvalidate(streamRef: FSEventStreamRef): Boolean

  def FSEventStreamRelease(streamRef: FSEventStreamRef): Boolean

  def FSEventStreamStart(streamRef: FSEventStreamRef): Boolean

  def FSEventStreamStop(streamRef: FSEventStreamRef): Unit

  def FSEventStreamScheduleWithRunLoop(streamRef: FSEventStreamRef,
                                       runLoop: CFRunLoopRef,
                                       runLoopMode: CFStringRef): Unit

  def FSEventStreamUnscheduleFromRunLoop(streamRef: FSEventStreamRef,
                                         runLoop: CFRunLoopRef,
                                         runLoopMode: CFStringRef): Unit

  def CFRunLoopGetCurrent(): CFRunLoopRef

  def CFRunLoopRun(): Unit

  def CFRunLoopStop(rl: CFRunLoopRef): Unit
}

object CFRunLoopThread {
  final val mode = CFStringRef.toCFString("kCFRunLoopDefaultMode")
}

private class CFRunLoopThread extends Thread("WatchService") {

  import CarbonAPI.INSTANCE._

  lazy val runLoop: CFRunLoopRef = CFRunLoopGetCurrent()
  private[this] val initLatch = new java.util.concurrent.CountDownLatch(1)
  private[this] val signalLatch = new java.util.concurrent.CountDownLatch(1)
  this.setDaemon(true)
  this.start()
  initLatch.await()
  def signal() = { signalLatch.countDown() }

  override def run() = {
    runLoop // Need to touch this variable to set the RunLoop to this thread
    initLatch.countDown()
    signalLatch.await()
    CFRunLoopRun()
  }
}

object EventStreamCreateFlags {
  val None = 0
  val UseCFTypes = 1
  val NoDefer = 1 << 1
  val WatchRoot = 1 << 2
  val IgnoreSelf = 1 << 3
  val FileEvents = 1 << 4
  val MarkSelf = 1 << 5
  val UseExtendedData = 1 << 6
  def flagName(flag: Int) = flag match {
    case None            => "None"
    case UseCFTypes      => "UseCFTypes"
    case NoDefer         => "NoDefer"
    case WatchRoot       => "WatchRoot"
    case IgnoreSelf      => "IgnoreSelf"
    case FileEvents      => "FileEvents"
    case MarkSelf        => "MarkSelf"
    case UseExtendedData => "UseExtendedData"
  }
  def getFlags(flag: Int) = if (flag == 0) Seq(None) else allFlags.filter(f => (f & flag) != 0)
  private val allFlags = Seq[Int](
    None,
    UseCFTypes,
    NoDefer,
    WatchRoot,
    IgnoreSelf,
    FileEvents,
    MarkSelf,
    UseExtendedData,
  )
}
object EventStreamFlags {
  val None = 0
  val MustScanSubDirs = 1
  val UserDropped = 1 << 1
  val KernelDropped = 1 << 2
  val EventIdsWrapped = 1 << 3
  val HistoryDone = 1 << 4
  val RootChanged = 1 << 5
  val Mount = 1 << 6
  val Unmount = 1 << 7
  val ItemChangeOwner = 1 << 8
  val ItemCreated = 1 << 9
  val ItemFinderInfoMod = 1 << 10
  val ItemInodeMetaMod = 1 << 11
  val ItemIsDir = 1 << 12
  val ItemIsFile = 1 << 13
  val ItemIsHardlink = 1 << 14
  val ItemIsLastHardlink = 1 << 15
  val ItemIsSymlink = 1 << 16
  val ItemModified = 1 << 17
  val ItemRemoved = 1 << 18
  val ItemRenamed = 1 << 19
  val ItemXattrMod = 1 << 20
  val OwnEvent = 1 << 21
  val ItemCloned = 1 << 22

  def flagName(flag: Int) = flag match {
    case None               => "None"
    case MustScanSubDirs    => "MustScanSubDirs"
    case UserDropped        => "UserDropped"
    case KernelDropped      => "KernelDropped"
    case EventIdsWrapped    => "EventIdsWrapped"
    case HistoryDone        => "HistoryDone"
    case RootChanged        => "RootChanged"
    case Mount              => "Mount"
    case Unmount            => "Unmount"
    case ItemChangeOwner    => "ItemChangeOwner"
    case ItemCreated        => "ItemCreated"
    case ItemFinderInfoMod  => "ItemFinderInfoMod"
    case ItemInodeMetaMod   => "ItemInodeMetaMod"
    case ItemIsDir          => "ItemIsDir"
    case ItemIsFile         => "ItemIsFile"
    case ItemIsHardlink     => "ItemIsHardlink"
    case ItemIsLastHardlink => "ItemIsLastHardlink"
    case ItemIsSymlink      => "ItemIsSymlink"
    case ItemModified       => "ItemModified"
    case ItemRemoved        => "ItemRemoved"
    case ItemRenamed        => "ItemRenamed"
    case ItemXattrMod       => "ItemXattrMod"
    case OwnEvent           => "OwnEvent"
    case ItemCloned         => "ItemCloned"
    case _                  => "Unknown"
  }
  def getFlags(flag: Int) = if (flag == 0) Seq(None) else allFlags.filter(f => (f & flag) != 0)
  private val allFlags = Seq[Int](
    MustScanSubDirs,
    UserDropped,
    KernelDropped,
    EventIdsWrapped,
    HistoryDone,
    RootChanged,
    Mount,
    Unmount,
    ItemChangeOwner,
    ItemCreated,
    ItemFinderInfoMod,
    ItemInodeMetaMod,
    ItemIsDir,
    ItemIsFile,
    ItemIsHardlink,
    ItemIsLastHardlink,
    ItemIsSymlink,
    ItemModified,
    ItemRemoved,
    ItemRenamed,
    ItemXattrMod,
    OwnEvent,
    ItemCloned,
  )
}
