package com.swoval.files;

import com.swoval.files.DataViews.Entry;
import com.swoval.files.Executor.Thread;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public abstract class MonitoredFileTreeViewImpl<T> implements MonitoredFileTreeView<T> {
  //  private final FileCachePathWatcher<DataViews.Entry<T>> pathWatcher;
  //  private final DataView<T> dataView;
  //  private final Executor executor;
  //
  //  MonitoredFileTreeViewImpl(
  //      final FileCachePathWatcher<DataViews.Entry<T>> pathWatcher,
  //      final DataView<T> dataView,
  //      final Executor executor) {
  //    this.pathWatcher = pathWatcher;
  //    this.dataView = dataView;
  //  }
  //
  //  @Override
  //  public List<Entry<T>> listEntries(
  //      final Path path, final int maxDepth, final Filter<? super Entry<T>> filter) {
  //    return dataView.listEntries(path, maxDepth, filter);
  //  }
  //
  //  @Override
  //  public List<TypedPath> list(
  //      final Path path, final int maxDepth, final Filter<? super TypedPath> filter)
  //      throws IOException {
  //    return dataView.list(path, maxDepth, filter);
  //  }
  //
  //  @Override
  //  public int addObserver(final Observer<Entry<T>> observer) {
  //    return pathWatcher.addObserver(observer);
  //  }
  //
  //  @Override
  //  public void removeObserver(int handle) {
  //    pathWatcher.removeObserver(handle);
  //  }
  //
  //  @Override
  //  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
  //    return executor.block(
  //        new Function<Thread, Boolean>() {
  //          @Override
  //          public Boolean apply(Thread thread) throws Exception {
  //            final Either<IOException, Boolean> = pathWatcher.register(path, maxDepth, thread);
  //          }
  //        }
  //    )
  //  }
  //
  //  @Override
  //  public void unregister(final Path path) {
  //    pathWatcher.unregister(path);
  //  }
  //
  //  @Override
  //  public void close() {
  //    pathWatcher.close();
  //  }
}
