package com.swoval.files

import com.swoval.files.apple.Flags
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import DirectoryWatcher._

object DirectoryWatcher {

  /**
 Callback to run when monitored files are created, updated and deleted.
   */
  trait Callback {

    def apply(event: Event): Unit

  }

  /**
   * Create a DirectoryWatcher for the runtime platform.
   *
   * @param latency The latency used by the [[AppleDirectoryWatcher]] on osx
   * @param timeUnit The TimeUnit or the latency parameter
   * @param flags Flags used by the apple directory watcher
   * @param callback [[Callback]] to run on file events
   * @return DirectoryWatcher for the runtime platform
   *     initialized
   *     initialization
   */
  def defaultWatcher(latency: Long,
                     timeUnit: TimeUnit,
                     flags: Flags.Create,
                     callback: Callback): DirectoryWatcher =
    if (Platform.isMac)
      new AppleDirectoryWatcher(timeUnit.toNanos(latency) / 1.0e9, flags, callback)
    else new NioDirectoryWatcher(callback)

  /**
   * Create a platform compatible DirectoryWatcher.
   *
   * @param callback [[Callback]] to run on file events
   * @return DirectoryWatcher for the runtime platform
   *     initialized
   *     initialization
   */
  def defaultWatcher(callback: Callback): DirectoryWatcher =
    defaultWatcher(10,
                   TimeUnit.MILLISECONDS,
                   new Flags.Create().setNoDefer().setFileEvents(),
                   callback)

  /**
   * Instantiates new [[DirectoryWatcher]] instances with a [[Callback]]. This is primarily
   * so that the [[DirectoryWatcher]] in [[FileCache]] may be changed in testing.
   */
  trait Factory {

    def create(callback: Callback): DirectoryWatcher

  }

  val DEFAULT_FACTORY: Factory = new Factory() {
    override def create(callback: Callback): DirectoryWatcher =
      defaultWatcher(callback)
  }

  object Event {

    val Create: Kind = new Kind("Create")

    val Delete: Kind = new Kind("Delete")

    val Modify: Kind = new Kind("Modify")

    val Overflow: Kind = new Kind("Overflow")

    /**
     * An enum like class to indicate the type of file event. It isn't an actual enum because the
     * scala.js codegen has problems with enum types.
     */
    class Kind(private val name: String) {

      override def toString(): String = name

      override def equals(other: Any): Boolean = other match {
        case other: Kind => other.name == this.name
        case _           => false

      }

      override def hashCode(): Int = name.hashCode

    }

  }

  /**
 Container for [[DirectoryWatcher]] events
   */
  class Event(val path: Path, val kind: Event.Kind) {

    override def equals(other: Any): Boolean = other match {
      case other: Event => {
        val that: Event = other
        this.path == that.path && this.kind == that.kind
      }
      case _ => false

    }

    override def hashCode(): Int = path.hashCode ^ kind.hashCode

    override def toString(): String = "Event(" + path + ", " + kind + ")"

  }

}

/**
 * Watches directories for file changes. The api permits recursive watching of directories unlike
 * the [[https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html  java.nio.file.WatchService]]. Some of the behavior may vary by platform due to
 * fundamental differences in the underlying file event apis. For example, Linux doesn't support
 * recursive directory monitoring via inotify, so it's possible in rare cases to miss file events
 * for newly created files in newly created directories. On OSX, it is difficult to disambiguate
 * file creation and modify events, so the [[DirectoryWatcher.Event.Kind]] is best effort, but
 * should not be relied upon to accurately reflect the state of the file.
 */
abstract class DirectoryWatcher extends AutoCloseable {

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @param recursive Toggles whether or not to monitor subdirectories
   * @return true if the registration is successful
   */
  def register(path: Path, recursive: Boolean): Boolean

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @return true if the registration is successful
   */
  def register(path: Path): Boolean = register(path, true)

  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  def unregister(path: Path): Unit

  /**
 Catch any exceptions in subclasses.
   */
  override def close(): Unit = {}

}
