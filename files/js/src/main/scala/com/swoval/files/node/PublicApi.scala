package com.swoval.files.node

import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeViews.{ Observer => SObserver }
import com.swoval.files.node.Converters._
import com.swoval.files.{
  TypedPaths,
  FileTreeDataViews => SFileTreeDataViews,
  FileTreeRepositories => SFileTreeRepositories,
  FileTreeRepository => SFileTreeRepository,
  PathWatcher => SPathWatcher,
  PathWatchers => SPathWatchers,
  TypedPath => STypedPath
}
import com.swoval.functional
import com.swoval.functional.Filter

import scala.collection.JavaConverters._
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.UndefOr
import scala.scalajs.js.annotation.{ JSExportAll, JSExportTopLevel }

@JSExportTopLevel("FileTreeRepositories")
@JSExportAll
object FileTreeRepositories {

  /**
   * Create a file tree repository that follows symlinks.
   *
   * @param converter function that converts a [[TypedPath]] to a generic type
   * @tparam T the value type of the cache entries
   * @return a file tree repository.
   */
  def followSymlinks[T <: AnyRef](
      converter: js.UndefOr[js.Function1[TypedPath, T]]): FileTreeRepository[T] = {
    val underlying =
      SFileTreeRepositories.followSymlinks(
        new Converter[T] {
          val function = converter.toOption match {
            case Some(f) =>
              (typedPath: STypedPath) =>
                f(new TypedPath(typedPath))
            case None =>
              (typedPath: STypedPath) =>
                new TypedPath(typedPath).asInstanceOf[T]
          }
          override def apply(typedPath: STypedPath): T = function(typedPath)
        },
      )
    new FileTreeRepository[T](underlying)
  }
}

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the [[FileTreeRepository.register]] method. Once a Path is added the cache, its
 * contents may be retrieved using the [[FileTreeRepository.list]] method. The cache stores the
 * path information in [[FileTreeDataViews.Entry]] instances.
 *
 * <p>A default implementation is provided by [[FileTreeRepositories.get]]. The user may cache
 * arbitrary information in the cache by customizing the [[Converter]] that is passed into the
 * factory [[FileTreeRepositories.get]].
 *
 * <p>The cache allows the user to register a regular file, directory or symbolic link. After
 * registration, the cache should monitor the path (and in the case of symbolic links, the target of
 * the link) for updates. Whenever an update is detected, the cache updates its internal
 * representation of the file system. When that is complete, it will notify all of the registered
 * [[com.swoval.files.Observers]] of the change. In general, the update that is sent in the
 * callback will be visible if the user lists the relevant path. It is however, possible that if the
 * file is being updated rapidly that the internal state of the cache may change in between the
 * callback being invoked and the user listing the path. Once the file system activity settles down,
 * the cache should always end up in a consistent state where it mirrors the state of the file
 * system.
 *
 * <p>The semantics of the list method are very similar to the linux `ls` tool. Listing a directory
 * returns all of the subdirectories and files contained in the directory and the empty list if the
 * directory is empty. Listing a file, however, will return the entry for the file if it exists and
 * the empty list otherwise.
 *
 * @tparam T the type of data stored in the [[FileTreeDataViews.Entry]] instances for the cache
 */
@JSExportTopLevel("FileTreeRepository")
@JSExportAll
class FileTreeRepository[T <: AnyRef] protected[node] (
    private[this] val underlying: SFileTreeRepository[T]) {

  /**
   * Shutdown the repository, freeing any native resources.
   */
  def close(): Unit = underlying.close()

  /**
   * Add an observer of events.
   *
   * @param observer the [[Observer]] to add
   * @return the handle to the observer.
   */
  def addObserver(observer: Observer[FileTreeDataViews.Entry[T]]): Int =
    underlying.addObserver(observer.toSwoval((e: SFileTreeDataViews.Entry[T]) => e.toJS))

  /**
   * Add an observer of cache events.
   *
   * @param observer the [[CacheObserver]] to add
   * @return the handle to the observer.
   */
  def addCacheObserver(cacheObserver: CacheObserver[T]): Int =
    underlying.addCacheObserver(cacheObserver.toSwoval)

  /**
   * List all of the files for the path that are accepted by the filter.
   *
   * @param path the path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param maxDepth the maximum depth of subdirectories to return. Default value: Integer.MAX_VALUE.
   * @param filter include only paths accepted by this. By default it accepts all paths.
   * @return a List of [[TypedPath]] instances accepted by the filter.
   */
  def list(path: String,
           maxDepth: UndefOr[Int],
           filter: UndefOr[js.Function1[TypedPath, Boolean]]): js.Array[TypedPath] = {
    val jsFilter: Filter[STypedPath] = new Filter[STypedPath] {
      val f = filter.toOption match {
        case Some(f) =>
          (typedPath: STypedPath) =>
            f(new TypedPath(typedPath))
        case _ =>
          (_: STypedPath) =>
            true
      }
      override def accept(typedPath: STypedPath): Boolean = f(typedPath)
    }
    underlying
      .list(Paths.get(path), maxDepth.toOption.getOrElse(Integer.MAX_VALUE), jsFilter)
      .asScala
      .view
      .map(new TypedPath(_))
      .toJSArray
  }

  /**
   * List all of the files for the path that are accepted by the filter.
   *
   * @param path the path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param maxDepth the maximum depth of subdirectories to return. Default value: Integer.MAX_VALUE.
   * @param filter include only paths accepted by this. By default it accepts all paths.
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this CachedDirectory or if it is a subdirectory, but the
   *     CachedDirectory was created without the recursive flag.
   */
  def listEntries(path: String,
                  maxDepth: UndefOr[Int],
                  filter: UndefOr[js.Function1[FileTreeDataViews.Entry[T], Boolean]])
    : js.Array[FileTreeDataViews.Entry[T]] = {
    val jsFilter: Filter[SFileTreeDataViews.Entry[T]] =
      new Filter[SFileTreeDataViews.Entry[T]] {
        val f = filter.toOption match {
          case Some(f) =>
            (entry: SFileTreeDataViews.Entry[T]) =>
              f(entry.toJS)
          case _ =>
            (_: SFileTreeDataViews.Entry[T]) =>
              true
        }
        override def accept(entry: SFileTreeDataViews.Entry[T]): Boolean = f(entry)
      }
    underlying
      .listEntries(Paths.get(path), maxDepth.toOption.getOrElse(Integer.MAX_VALUE), jsFilter)
      .asScala
      .view
      .map(_.toJS)
      .toJSArray
  }

  /**
   * Register a path with the cache. A successful call to this method will both start monitoring of
   * the path add will fill the cache for this path.
   *
   * @param path the directory to watch for file events and to add to the cache
   * @param maxDepth the maximum maxDepth of subdirectories to watch. Default value: Integer.MAX_VALUE.
   * @return an [[com.swoval.functional.Either]] that will return a right value when no
   *     exception is thrown. The right value will be true if the path has not been previously
   *     registered. The [[com.swoval.functional.Either]] will be a left if any IOException is
   *     thrown attempting to register the path.
   */
  def register(path: String, maxDepth: js.UndefOr[Int]): Either[IOException, Boolean] =
    underlying.register(Paths.get(path), maxDepth.getOrElse(Integer.MAX_VALUE)).asScala

  /**
   * Remove an observer that was previously added via [[FileTreeRepository.addObserver]] or
   * [[FileTreeRepository.addCacheObserver]].
   * @param handle the handle returned by [[FileTreeRepository.addObserver]] or
   * [[FileTreeRepository.addCacheObserver]] corresponding to the observer to remove.
   */
  def removeObserver(handle: Int): Unit = underlying.removeObserver(handle)

  /**
   * Unregister a path from the cache. This removes the path from monitoring and from the cache so
   * long as the path isn't covered by another registered path. For example, if the path /foo was
   * previously registered, after removal, no changes to /foo or files in /foo should be detected by
   * the cache. Moreover, calling [[FileTreeRepository.list]] for /foo should return an empty
   * list. If, however, we register both /foo recursively and /foo/bar (recursively or not), after
   * unregistering /foo/bar, changes to /foo/bar should continue to be detected and /foo/bar should
   * be included in the list returned by [[FileTreeRepository.list]].
   *
   * @param path the path to unregister
   */
  def unregister(path: String): Unit = underlying.unregister(Paths.get(path))
}

@JSExportTopLevel("PathWatchers")
@JSExportAll
object PathWatchers {

  /**
   * Create a [[PathWatcher]] that follows symlinks for the runtime platform.
   *
   * @return an appropriate [[PathWatcher]] for the runtime platform.
   */
  def followLinks(): PathWatcher =
    new PathWatcher(SPathWatchers.followSymlinks())

  /**
   * Create a [[PathWatcher]] that follows symlinks for the runtime platform.
   *
   * @return an appropriate [[PathWatcher]] for the runtime platform.
   */
  def noFollowLinks(): PathWatcher =
    new PathWatcher(SPathWatchers.noFollowSymlinks())

  /**
   * Create a path watcher that periodically polls the file system to detect changes
   *
   * @param followLinks toggles whether or not the targets of symbolic links should be monitored
   * @param intervalMS minimum duration in milliseconds between when polling ends and the next poll begins
   * @return the [[PathWatcher]] backed by a [[com.swoval.files.PollingPathWatcher]].
   */
  def polling(followLinks: js.UndefOr[Boolean], intervalMS: js.UndefOr[Double]): PathWatcher = {
    new PathWatcher(
      SPathWatchers.polling(followLinks.toOption.getOrElse(true),
                            intervalMS.toOption.getOrElse(500.0).toLong,
                            TimeUnit.MILLISECONDS))
  }

  /**
   * A file event for a [[PathWatcher]].
   * @param getTypedPath the [[TypedPath]] to which the event corresponds.
   * @param getKind the kind of event. The possible kinds are { "Create", "Delete", "Error", "Modify" }.
   */
  @JSExportAll
  case class Event(getTypedPath: TypedPath, getKind: String)
}

/**
 * Watches directories for file changes. The api permits recursive watching of directories unlike
 * the [[https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html
 * java.nio.file.WatchService]].
 * Some of the behavior may vary by platform due to fundamental differences in the underlying
 * file event apis. For example, Linux doesn't support recursive directory monitoring via inotify,
 * so it's possible in rare cases to miss file events for newly created files in newly created
 * directories. On OSX, it is difficult to disambiguate file creation and modify events, so the
 * kind of event is best effort, but should not be relied upon to accurately reflect the state of
 * the file.
 */
@JSExportTopLevel("PathWatcher")
@JSExportAll
class PathWatcher protected[node] (
    private[this] val underlying: SPathWatcher[SPathWatchers.Event]) {

  /**
   * Register a path to monitor for file events. The watcher will only watch child subdirectories up
   * to maxDepth. For example, with a directory structure like /foo/bar/baz, if we register the path
   * /foo/ with maxDepth 0, we will be notified for any files that are created, updated or deleted
   * in foo, but not bar. If we increase maxDepth to 1, then the files in /foo/bar are monitored,
   * but not the files in /foo/bar/baz.
   *
   * @param path the directory to watch for file events
   * @param maxDepth the maximum maxDepth of subdirectories to watch
   * @return an [[com.swoval.functional.Either]] containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  def register(path: String, maxDepth: Int): JSEither[IOException, Boolean] =
    JSEither(underlying.register(Paths.get(path), maxDepth))

  /**
   * Stop watching a path.
   *
   * @param path the path to remove from monitoring
   */
  def unregister(path: String): Unit = underlying.unregister(Paths.get(path))

  /**
   * Shutdown the [[PathWatcher]], freeing any native resources.
   */
  def close(): Unit = underlying.close()

  /**
   * Add an observer of events.
   *
   * @param observer the [[Observer]] to add
   * @return the handle to the observer.
   */
  def addObserver(observer: Observer[PathWatchers.Event]): Int =
    underlying.addObserver(observer.toSwoval(_.toJS))

  /**
   * Remove an observer that was previously added via [[PathWatcher.addObserver]]
   * @param handle the handle returned by [[PathWatcher.addObserver]] corresponding to the observer
   *               to remove.
   */
  def removeObserver(handle: Int): Unit = underlying.removeObserver(handle)
}

/**
 * An observer of cache events.
 * @constructor
 * @param onNext
 * @param onError
 * @tparam T
 */
@JSExportTopLevel("Observer")
@JSExportAll
class Observer[T <: AnyRef](val onNext: js.Function1[T, Unit],
                            val onError: js.UndefOr[js.Function1[Throwable, Unit]])

/**
 * An observer of cache events.
 *
 * @constructor
 * @param onCreate the callback to invoke for newly created files
 * @param onDelete the callback to invoke for recently deleted files
 * @param onUpdate the callback to invoke for recently modified files
 * @param onError the callback to invoke when an error occurs. By default, the error is ignored.
 */
@JSExportTopLevel("CacheObserver")
@JSExportAll
class CacheObserver[T](
    val onCreate: js.Function1[FileTreeDataViews.Entry[T], Unit],
    val onDelete: js.Function1[FileTreeDataViews.Entry[T], Unit],
    val onUpdate: js.Function2[FileTreeDataViews.Entry[T], FileTreeDataViews.Entry[T], Unit],
    val onError: js.UndefOr[js.Function1[IOException, Unit]]
)

@JSExportTopLevel("CacheObservers")
@JSExportAll
object CacheObservers {

  /**
   * Create a CacheObserver that executes the same function for each kind of event. For updates,
   * the new value is passed into the provided function.
   *
   * @param onEvent the callback to invoke when a file system event is detected.
   * @param onError the callback to invoke when an error occurs. By default, it's a no-op.
   * @tparam T the generic type of the cache entries.
   * @return a [[CacheObserver]].
   */
  def get[T](onEvent: js.Function1[FileTreeDataViews.Entry[T], Unit],
             onError: js.UndefOr[js.Function1[IOException, Unit]]): CacheObserver[T] =
    new CacheObserver[T](
      onEvent,
      onEvent,
      ((_: FileTreeDataViews.Entry[T], e: FileTreeDataViews.Entry[T]) => onEvent(e)): js.Function2[
        FileTreeDataViews.Entry[T],
        FileTreeDataViews.Entry[T],
        Unit],
      onError
    )
}

@JSExportTopLevel("FileTreeDataViews")
@JSExportAll
object FileTreeDataViews {

  /**
   * A cache entry for a particular file.
   * @constructor
   * @param getTypedPath the [[TypedPath]] to which this [[Entry]] corresponds.
   * @param getValue the [[JSEither]] that represents the cache value. When an error occurs, the
   *                 [[JSEither]] will be a [[JSLeftProjection]].
   * @tparam T the generic type of the cache entry.
   */
  @JSExportAll
  case class Entry[T](getTypedPath: TypedPath, getValue: JSEither[IOException, T])
}

/**
 * Represents a value that can take one of two types. It is right biased, so that the
 * `get` and `getOrElse` correspond to the right value type.
 * @tparam L The type of the "left" value
 * @tparam R The type of the "right" value
 */
@JSExportTopLevel("Either")
@JSExportAll
class JSEither[L, R] protected (private[this] val either: functional.Either[L, R]) {

  /**
   * Projects this either to [[JSLeftProjection]]. This is potentially unsafe to call without checking
   * [[JSEither.isLeft]] or [[JSEither.isRight]].
   * @return the [[JSLeftProjection]]
   */
  def left: JSLeftProjection[L, R] =
    new JSLeftProjection[L, R](functional.Either.leftProjection(either))

  /**
   * True if the [[JSEither]] represents the left value type
   * @return true if the [[JSEither]] represents the left value type
   */
  def isLeft: Boolean = either.isLeft()

  /**
   * True if the [[JSEither]] represents the right value type
   * @return true if the [[JSEither]] represents the right value type
   */
  def isRight: Boolean = either.isRight()

  /**
   * Return the right value if this [[JSEither]] represents a right value. Otherwise throw a
   * [[com.swoval.functional.Either.NotRightException]].
   * @return the right value.
   */
  def get: R = either.get()

  /**
   * Return the right value or a default if this either represents a left value.
   * @return the right value or a default if this either represents a left value.
   */
  def getOrElse[R0 >: R](r: R0): R0 = functional.Either.getOrElse(either, r)

  override def hashCode(): Int = either.hashCode()
  override def equals(other: Any): Boolean = either.equals(other)
}
private[node] object JSEither {
  def apply[L, R](either: functional.Either[L, R]): JSEither[L, R] = new JSEither(either)
}

/**
 * Represents a left projected [[JSEither]], which adds
 * @param left
 * @tparam L
 * @tparam R
 */
@JSExportAll
class JSLeftProjection[+L, +R](private[this] val left: functional.Either.Left[L, R]) {

  /**
   * Returns the value represented by this [[JSLeftProjection]].
   * @return the value.
   */
  def getValue: L = left.getValue
  override def toString(): String = left.toString()
  override def equals(other: Any): Boolean = left.equals(other)
  override def hashCode(): Int = left.hashCode()
}

/**
 * Represents a file system path. Provides (possibly) fast accessors for some properties of the
 * file. It is generally unsafe to cache as the properties do not change if the underlying
 * file is modified.
 */
@JSExportTopLevel("TypedPath")
@JSExportAll
class TypedPath(private[this] val typedPath: STypedPath) {
  def getPath: String = typedPath.getPath.toString

  /**
   * Returns true if the path at [[TypedPath.getPath]] existed when this [[TypedPath]] was created.
   * @return true if this [[TypedPath]] exists.
   */
  def exists: Boolean = typedPath.exists

  /**
   * Returns true if the path at [[TypedPath.getPath]] was a directory when this [[TypedPath]] was
   * created.
   * @return true if this [[TypedPath]] represents a directory.
   */
  def isDirectory: Boolean = typedPath.isDirectory

  /**
   * Returns true if the path at [[TypedPath.getPath]] was a regular file when this [[TypedPath]]
   * was created.
   * @return true if this [[TypedPath]] represents a regular file.
   */
  def isFile: Boolean = typedPath.isFile

  /**
   * Returns true if the path at [[TypedPath.getPath]] was a symbolic link when this [[TypedPath]]
   * was created.
   * @return true if this [[TypedPath]] represents a symbolic link.
   */
  def isSymbolicLink: Boolean = typedPath.isSymbolicLink

  /**
   * Return the right value if this [[JSEither]] represents a right value. Otherwise throw a
   * [[com.swoval.functional.Either.NotRightException]].
   * @return the right value.
   */
  override def toString = s"TypedPath($getPath)"
}

private[files] object Converters {
  implicit class SwovalEitherOps[L, R](val either: functional.Either[L, R]) extends AnyVal {
    def asScala: Either[L, R] =
      if (either.isRight) Right(either.get)
      else Left(functional.Either.leftProjection(either).getValue)
    def asJS: JSEither[L, R] = JSEither(either)
  }
  implicit class EitherOps[L, R](val either: Either[L, R]) extends AnyVal {
    def asSwoval: functional.Either[L, R] =
      if (either.isRight) functional.Either.right(either.right.get)
      else functional.Either.left(either.left.get)
    def asJS: JSEither[L, R] = JSEither(either.asSwoval)
  }
  implicit class JSEitherOps[L, R](val either: JSEither[L, R]) extends AnyVal {
    def asScala: Either[L, R] =
      if (either.isRight) Right(either.get) else Left(either.left.getValue)
  }
  implicit class EntryOps[T](val entry: SFileTreeDataViews.Entry[T]) extends AnyVal {
    def toJS: FileTreeDataViews.Entry[T] =
      FileTreeDataViews.Entry(entry.getTypedPath.toJS, entry.getValue.asJS)
  }
  implicit class TypedPathOps(val typedPath: STypedPath) extends AnyVal {
    def toJS: TypedPath = new TypedPath(typedPath)
  }
  implicit class JSObserverOps[T <: AnyRef](val observer: Observer[T]) extends AnyVal {
    def toSwoval[U](f: U => T): SObserver[U] =
      new SObserver[U] {
        override def onError(t: Throwable): Unit = observer.onError.foreach(_(t))
        override def onNext(u: U): Unit = observer.onNext(f(u))
      }
  }
  implicit class JSCacheObserverOps[T <: AnyRef](val observer: CacheObserver[T]) extends AnyVal {
    def toSwoval: SFileTreeDataViews.CacheObserver[T] = new SFileTreeDataViews.CacheObserver[T] {
      override def onError(ioException: IOException): Unit =
        observer.onError.foreach(_(ioException))
      override def onCreate(newEntry: SFileTreeDataViews.Entry[T]): Unit =
        observer.onCreate(newEntry.toJS)
      override def onDelete(oldEntry: SFileTreeDataViews.Entry[T]): Unit =
        observer.onDelete(oldEntry.toJS)
      override def onUpdate(oldEntry: SFileTreeDataViews.Entry[T],
                            newEntry: SFileTreeDataViews.Entry[T]): Unit =
        observer.onUpdate(oldEntry.toJS, newEntry.toJS)
    }
  }
  implicit class EventOps(val event: SPathWatchers.Event) extends AnyVal {
    def toJS: PathWatchers.Event =
      PathWatchers.Event(event.typedPath.toJS, event.kind.toString)
  }
}
