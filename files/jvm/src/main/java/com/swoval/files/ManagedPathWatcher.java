package com.swoval.files;

interface ManagedPathWatcher extends PathWatcher<PathWatchers.Event> {
  void update(final TypedPath path);
}
