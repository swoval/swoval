package com.swoval.files;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

class DirectoryListers {
  private DirectoryListers() {}

  @SuppressWarnings({"unchecked", "EmptyCatchBlock"})
  static DirectoryLister[] init() {
    final String className = System.getProperty("swoval.directory.lister");
    DirectoryLister directoryLister = null;
    if (className != null) {
      try {
        Constructor<DirectoryLister> cons =
            ((Class<DirectoryLister>) Class.forName(className)).getDeclaredConstructor();
        cons.setAccessible(true);
        directoryLister = cons.newInstance();
      } catch (ClassNotFoundException
          | NoSuchMethodException
          | ClassCastException
          | IllegalAccessException
          | InstantiationException
          | InvocationTargetException e) {
      }
    }
    NativeDirectoryLister nativeDirectoryLister;
    try {
      nativeDirectoryLister = new NativeDirectoryLister();
    } catch (final UnsatisfiedLinkError | RuntimeException e) {
      nativeDirectoryLister = null;
    }
    return new DirectoryLister[] {nativeDirectoryLister, directoryLister};
  }
}
