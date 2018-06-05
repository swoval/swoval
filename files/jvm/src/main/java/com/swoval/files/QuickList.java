package com.swoval.files;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides a fast method #{@link QuickList#list(Path, int, boolean)} for listing the files in a
 * directory. The implementation may be controlled using the system property -Dswoval.quicklister.
 * For example, to make this class use the java nio based implementation, the application could be
 * started with -Dswoval.quicklister=com.swoval.files.NioQuickLister.
 */
@SuppressWarnings({"unchecked", "EmptyCatchBlock"})
public abstract class QuickList {
  private static final QuickLister INSTANCE;

  static {
    final String className = System.getProperty("swoval.quicklister");
    QuickLister quickLister = null;
    if (className != null) {
      try {
        quickLister =
            ((Class<QuickLister>) Class.forName(className)).getConstructor().newInstance();
      } catch (ClassNotFoundException
          | NoSuchMethodException
          | ClassCastException
          | IllegalAccessException
          | InstantiationException
          | InvocationTargetException e) {
      }
    }
    INSTANCE =
        quickLister == null
            ? (Platform.isJVM() && NativeQuickLister.available())
                ? new NativeQuickLister()
                : new NioQuickLister()
            : quickLister;
  }

  /**
   * Lists the files and directories in {@code path} following symbolic links. The returned paths
   * may not be normalized. For example, if /foo/bar links to /baz/buzz, when listing /foo, the
   * {@link QuickFile} for buzz may be /foo/../baz/buzz". This behavior may change in a future
   * version.
   *
   * @param path The path to list
   * @param maxDepth The maximum maxDepth of the file system tree to traverse
   * @return a List of {@link QuickFile} instances
   * @throws IOException when the path isn't listable because it isn't a directory, access is denied
   *     or the path doesn't exist. May also throw due to any io error.
   */
  public static List<QuickFile> list(final Path path, final int maxDepth) throws IOException {
    return INSTANCE.list(path, maxDepth, true);
  }

  /**
   * Lists the files and directories in {@code path}. The returned paths may not be normalized. For
   * example, if /foo/bar links to /baz/buzz, when listing /foo, the {@link QuickFile} for buzz may
   * be /foo/../baz/buzz". This behavior may change in a future version.
   *
   * @param path The path to list
   * @param maxDepth The maximum maxDepth of the file system tree to traverse
   * @param followLinks Toggles whether or not to follow symbolic links in the path
   * @return a List of {@link QuickFile} instances
   * @throws IOException when the path isn't listable because it isn't a directory, access is denied
   *     or the path doesn't exist. May also throw due to any io error.
   */
  public static List<QuickFile> list(final Path path, final int maxDepth, final boolean followLinks)
      throws IOException {
    return INSTANCE.list(path, maxDepth, followLinks);
  }
}
