package com.swoval.files

import java.util.Map.Entry
import com.swoval.files.Directory.OnError
import com.swoval.files.DirectoryWatcher.Event
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Set
import java.util.concurrent.atomic.AtomicBoolean
import SymlinkWatcher._

object SymlinkWatcher {

  private class RegisteredPath(val path: Path, val isDirectory: Boolean, base: Path) {

    val paths: Set[Path] = new HashSet()

    paths.add(base)

  }

}

/**
 * Monitors symlink targets. The [[SymlinkWatcher]] maintains a mapping of symlink targets to
 * symlink. When the symlink target is modified, the watcher will detect the update and invoke a
 * provided [[com.swoval.functional.Consumer]] for the symlink.
 */
class SymlinkWatcher(handleEvent: Consumer[Path],
                     factory: DirectoryWatcher.Factory,
                     private val onError: OnError,
                     executor: Executor)
    extends AutoCloseable {

  private val isClosed: AtomicBoolean = new AtomicBoolean(false)

  private val watchedSymlinksByDirectory: Map[Path, RegisteredPath] =
    new HashMap()

  private val watchedSymlinksByTarget: Map[Path, RegisteredPath] =
    new HashMap()

  private val internalExecutor: Executor =
    if (executor == null)
      Executor.make("com.swoval.files.SymlinkWatcher-callback-internalExecutor")
    else executor

  private def find(path: Path, map: Map[Path, RegisteredPath]): RegisteredPath = {
    val result: RegisteredPath = map.get(path)
    if (result != null) result
    else if (path == null || path.getNameCount == 0) null
    else {
      val parent: Path = path.getParent
      if (parent == null || parent.getNameCount == 0) null
      else find(parent, map)
    }
  }

  val callback: Consumer[Event] = new Consumer[Event]() {
    override def accept(event: Event): Unit = {
      SymlinkWatcher.this.internalExecutor.run(new Runnable() {
        override def run(): Unit = {
          val callbacks: List[Runnable] = new ArrayList[Runnable]()
          val path: Path = event.path
          val registeredPath: RegisteredPath =
            find(path, watchedSymlinksByTarget)
          if (registeredPath != null) {
            val relativized: Path = registeredPath.path.relativize(path)
            val it: Iterator[Path] = registeredPath.paths.iterator()
            while (it.hasNext) {
              val p: Path = it.next()
              callbacks.add(new Runnable() {
                override def run(): Unit = {
                  handleEvent.accept(p.resolve(relativized))
                }
              })
            }
          }
          if (!Files.exists(event.path)) {
            val parent: Path = event.path.getParent
            watchedSymlinksByTarget.remove(event.path)
            val registeredPath: RegisteredPath =
              watchedSymlinksByDirectory.get(parent)
            if (registeredPath != null) {
              registeredPath.paths.remove(event.path)
              if (registeredPath.paths.isEmpty) {
                watcher.unregister(parent)
                watchedSymlinksByDirectory.remove(parent)
              }
            }
          }
          val it: Iterator[Runnable] = callbacks.iterator()
          while (it.hasNext) it.next().run()
        }
      })
    }
  }

  /*
   * This declaration must go below the constructor for javascript codegen.
   */

  private val watcher: DirectoryWatcher =
    factory.create(callback, internalExecutor.copy())

  override def close(): Unit = {
    internalExecutor.run(new Runnable() {
      override def run(): Unit = {
        if (isClosed.compareAndSet(false, true)) {
          watcher.close()
          watchedSymlinksByTarget.clear()
          watchedSymlinksByDirectory.clear()
        }
      }
    })
  }

  /**
   * Start monitoring a symlink. As long as the target exists, this method will check if the parent
   * directory of the target is being monitored. If the parent isn't being registered, we register
   * it with the watch service. We add the target symlink to the set of symlinks watched in the
   * parent directory. We also add the base symlink to the set of watched symlinks for this
   * particular target.
   *
   * @param path The symlink base file.
   */
  def addSymlink(path: Path, isDirectory: Boolean, maxDepth: Int): Unit = {
    internalExecutor.run(new Runnable() {
      override def toString(): String = "Add symlink " + path

      override def run(): Unit = {
        if (!isClosed.get) {
          try {
            val realPath: Path = path.toRealPath()
            val targetRegistrationPath: RegisteredPath =
              watchedSymlinksByTarget.get(realPath)
            if (targetRegistrationPath == null) {
              val registrationPath: Path =
                if (isDirectory) realPath else realPath.getParent
              val registeredPath: RegisteredPath =
                watchedSymlinksByDirectory.get(registrationPath)
              if (registeredPath != null) {
                if (!isDirectory || registeredPath.isDirectory) {
                  registeredPath.paths.add(realPath)
                  val symlinkChildren: RegisteredPath =
                    watchedSymlinksByTarget.get(realPath)
                  if (symlinkChildren != null) {
                    symlinkChildren.paths.add(path)
                  }
                } else {
                  val result: Either[IOException, Boolean] =
                    watcher.register(registrationPath, maxDepth)
                  if (result.getOrElse(false)) {
                    val parentPath: RegisteredPath =
                      new RegisteredPath(registrationPath, true, realPath)
                    parentPath.paths.addAll(registeredPath.paths)
                    watchedSymlinksByDirectory.put(registrationPath, parentPath)
                    val symlinkPaths: RegisteredPath =
                      new RegisteredPath(realPath, true, path)
                    val existingSymlinkPaths: RegisteredPath =
                      watchedSymlinksByTarget.get(realPath)
                    if (existingSymlinkPaths != null) {
                      symlinkPaths.paths.addAll(existingSymlinkPaths.paths)
                    }
                    watchedSymlinksByTarget.put(realPath, symlinkPaths)
                  } else if (result.isLeft) {
                    onError.apply(registrationPath, result.left().getValue)
                  }
                }
              } else {
                val result: Either[IOException, Boolean] =
                  watcher.register(registrationPath, maxDepth)
                if (result.getOrElse(false)) {
                  watchedSymlinksByDirectory.put(
                    registrationPath,
                    new RegisteredPath(registrationPath, isDirectory, realPath))
                  watchedSymlinksByTarget.put(realPath,
                                              new RegisteredPath(realPath, isDirectory, path))
                } else if (result.isLeft) {
                  onError.apply(registrationPath, result.left().getValue)
                }
              }
            } else if (Files.isDirectory(realPath)) {
              onError.apply(path, new FileSystemLoopException(path.toString))
            } else {
              targetRegistrationPath.paths.add(path)
            }
          } catch {
            case e: IOException => onError.apply(path, e)

          }
        }
      }
    })
  }

  /**
   * Removes the symlink from monitoring. If there are no remaining targets in the parent directory,
   * then we remove the parent directory from monitoring.
   *
   * @param path The symlink base to stop monitoring
   */
  def remove(path: Path): Unit = {
    internalExecutor.block(new Runnable() {
      override def run(): Unit = {
        if (!isClosed.get) {
          var target: Path = null
          val it: Iterator[Entry[Path, RegisteredPath]] =
            watchedSymlinksByTarget.entrySet().iterator()
          while (it.hasNext && target == null) {
            val entry: Entry[Path, RegisteredPath] = it.next()
            if (entry.getValue.paths.remove(path)) {
              target = entry.getKey
            }
          }
          if (target != null) {
            val removalPath: Path =
              if (Files.isDirectory(target)) target else target.getParent
            val targetRegisteredPath: RegisteredPath =
              watchedSymlinksByTarget.get(target)
            if (targetRegisteredPath != null) {
              targetRegisteredPath.paths.remove(path)
              if (targetRegisteredPath.paths.isEmpty) {
                watchedSymlinksByTarget.remove(target)
                val registeredPath: RegisteredPath =
                  watchedSymlinksByDirectory.get(removalPath)
                if (registeredPath != null) {
                  registeredPath.paths.remove(target)
                  if (registeredPath.paths.isEmpty) {
                    watcher.unregister(removalPath)
                    watchedSymlinksByDirectory.remove(removalPath)
                  }
                }
              }
            }
          }
        }
      }
    })
  }

}
