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

  /**
   * Returns an instance of {@link com.swoval.files.QuickLister} that uses only apis available in
   * java.nio.file. This may be used on platforms for which there is no native implementation of
   * {@link com.swoval.files.QuickLister}.
   *
   * @return an instance of {@link com.swoval.files.QuickLister}.
   */
  public static QuickLister getNio() {
    return new QuickListerImpl(nioDirectoryLister);
  }

  /**
   * Returns an instance of {@link com.swoval.files.QuickLister} that uses native jni functions to
   * improve performance compared to the {@link com.swoval.files.QuickLister} returned by {@link
   * com.swoval.files.QuickListers#getNio()}.
   *
   * @return an instance of {@link com.swoval.files.QuickLister}.
   */
  public static QuickLister getNative() {
    return new QuickListerImpl(nativeDirectoryLister);
  }

  /**
   * Returns the default {@link com.swoval.files.QuickLister} for the runtime platform. If a native
   * implementation is present, it will be used. Otherwise, it will fall back to the java.nio.file
   * based implementation.
   *
   * @return an instance of {@link com.swoval.files.QuickLister}.
   */
  public static QuickLister getDefault() {
    return nativeDirectoryLister == null ? getNio() : getNative();
  }
}
