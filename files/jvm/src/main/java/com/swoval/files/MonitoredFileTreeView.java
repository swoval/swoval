package com.swoval.files;

import com.swoval.files.DataViews.Entry;

public interface MonitoredFileTreeView<T>
    extends DataView<T>, PathWatcher<Entry<T>>, AutoCloseable {}
