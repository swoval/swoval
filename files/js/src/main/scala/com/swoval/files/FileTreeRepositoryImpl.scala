// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.FileTreeViews.CacheObserver
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatchers.Event.Kind
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import com.swoval.functional.Filter
import com.swoval.runtime.ShutdownHooks
import java.io.IOException
import java.nio.file.Path
import java.util.List
import java.util.concurrent.atomic.AtomicBoolean
import FileTreeRepositoryImpl._

object FileTreeRepositoryImpl {

  abstract class Callback(private val typedPath: TypedPath, private val kind: Kind)
      extends Runnable
      with Comparable[Callback] {

    override def compareTo(that: Callback): Int = {
      val kindComparision: Int = this.kind.compareTo(that.kind)
      if (kindComparision == 0) this.typedPath.compareTo(that.typedPath)
      else kindComparision
    }

  }

}

class FileTreeRepositoryImpl[T <: AnyRef](private val directoryTree: FileCacheDirectoryTree[T],
                                          private val watcher: FileCachePathWatcher[T],
                                          executor: Executor)
    extends FileTreeRepository[T] {

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val internalExecutor: Executor = executor

  private val closeRunnable: Runnable = new Runnable() {
    override def run(): Unit = {
      if (closed.compareAndSet(false, true)) {
        internalExecutor.block(new Consumer[Executor.Thread]() {
          override def accept(thread: Executor.Thread): Unit = {
            ShutdownHooks.removeHook(shutdownHookId)
            watcher.close(thread)
            directoryTree.close(thread)
          }
        })
        internalExecutor.close()
      }
    }
  }

  private val shutdownHookId: Int = ShutdownHooks.addHook(1, closeRunnable)

  assert((executor != null))

  /**
 Cleans up the path watcher and clears the directory cache.
   */
  override def close(): Unit = {
    closeRunnable.run()
  }

  override def addObserver(observer: Observer[FileTreeDataViews.Entry[T]]): Int =
    addCacheObserver(new CacheObserver[T]() {
      override def onCreate(newEntry: Entry[T]): Unit = {
        observer.onNext(newEntry)
      }

      override def onDelete(oldEntry: Entry[T]): Unit = {
        observer.onNext(oldEntry)
      }

      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
        observer.onNext(newEntry)
      }

      override def onError(exception: IOException): Unit = {
        observer.onError(exception)
      }
    })

  override def removeObserver(handle: Int): Unit = {
    directoryTree.removeObserver(handle)
  }

  override def listEntries(
      path: Path,
      maxDepth: Int,
      filter: Filter[_ >: FileTreeDataViews.Entry[T]]): List[FileTreeDataViews.Entry[T]] =
    internalExecutor
      .block(new Function[Executor.Thread, List[FileTreeDataViews.Entry[T]]]() {
        override def apply(thread: Executor.Thread): List[FileTreeDataViews.Entry[T]] =
          directoryTree.listEntries(path, maxDepth, filter)
      })
      .get

  override def register(path: Path, maxDepth: Int): Either[IOException, Boolean] =
    internalExecutor
      .block(new Function[Executor.Thread, Boolean]() {
        override def apply(thread: Executor.Thread): Boolean =
          watcher.register(path, maxDepth, thread)
      })
      .castLeft(classOf[IOException], false)

  override def unregister(path: Path): Unit = {
    internalExecutor.block(new Consumer[Executor.Thread]() {
      override def accept(thread: Executor.Thread): Unit = {
        watcher.unregister(path, thread)
      }
    })
  }

  override def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath] =
    directoryTree.list(path, maxDepth, filter)

  override def addCacheObserver(observer: CacheObserver[T]): Int =
    directoryTree.addCacheObserver(observer)

}