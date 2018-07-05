package com.swoval.files;

/** Provides factories for {@link com.swoval.files.QuickLister} instances. */
public class QuickListers {
  private static final NioDirectoryLister nioDirectoryLister = new NioDirectoryLister();
  private static final NativeDirectoryLister nativeDirectoryLister;

  static {
    NativeDirectoryLister lister;
    try {
      lister = new NativeDirectoryLister();
    } catch (final UnsatisfiedLinkError e) {
      lister = null;
    }
    nativeDirectoryLister = lister;
  }

  public static QuickLister getNio() {
    return new QuickListerImpl(nioDirectoryLister);
  }

  public static QuickLister getNative() {
    return new QuickListerImpl(nativeDirectoryLister);
  }

  public static QuickLister getDefault() {
    return nativeDirectoryLister == null ? getNio() : getNative();
  }
}
