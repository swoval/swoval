package com.swoval.files;

interface WatchedDirectory extends AutoCloseable {

  /**
   * Is the underlying directory watcher valid?
   *
   * @return true if the underlying directory watcher is valid
   */
  boolean isValid();

  /** Cancel the watch on this directory. Handle all non-fatal exceptions. */
  @Override
  void close();
}

class WatchedDirectories {
  static WatchedDirectory INVALID =
      new WatchedDirectory() {
        @Override
        public boolean isValid() {
          return false;
        }

        @Override
        public void close() {}

        @Override
        public String toString() {
          return "Invalid";
        }
      };
}
