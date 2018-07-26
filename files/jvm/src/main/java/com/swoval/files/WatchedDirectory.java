package com.swoval.files;

interface WatchedDirectory extends AutoCloseable {
  /** Cancel the watch on this directory. Handle all non-fatal exceptions. */
  @Override
  void close();
}

class WatchedDirectories {
  static WatchedDirectory INVALID =
      new WatchedDirectory() {
        @Override
        public void close() {
        }

        @Override
        public String toString() {
          return "Invalid";
        }
      };
}
