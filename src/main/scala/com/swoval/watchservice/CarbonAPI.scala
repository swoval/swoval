package com.swoval.watchservice

import com.sun.jna.ptr.PointerByReference
import com.sun.jna.{Pointer => Ptr, _}

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
                    callBacks: Void /* unused */): CFArrayRef

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

  def FSEventStreamStart(streamRef: FSEventStreamRef): Boolean

  def FSEventStreamStop(streamRef: FSEventStreamRef): Unit

  def FSEventStreamScheduleWithRunLoop(streamRef: FSEventStreamRef,
                                       runLoop: CFRunLoopRef,
                                       runLoopMode: CFStringRef): Unit

  def CFRunLoopGetCurrent(): CFRunLoopRef

  def CFRunLoopRun(): Unit

  def CFRunLoopStop(rl: CFRunLoopRef): Unit
}

object CFRunLoopThread {
  private val mode = CFStringRef.toCFString("kCFRunLoopDefaultMode")
}

private class CFRunLoopThread(val streamRef: FSEventStreamRef) extends Thread("WatchService") {

  import CarbonAPI.INSTANCE._

  lazy val runLoop: CFRunLoopRef = CFRunLoopGetCurrent()
  private[this] val latch = new java.util.concurrent.CountDownLatch(1)
  this.setDaemon(true)
  this.start()
  latch.await()

  override def run() {
    runLoop
    addStream(streamRef)
    latch.countDown()
    CFRunLoopRun()
  }

  def addStream(streamRef: FSEventStreamRef): Unit = {
    FSEventStreamScheduleWithRunLoop(streamRef, runLoop, CFRunLoopThread.mode)
    FSEventStreamStart(streamRef)
  }
}
