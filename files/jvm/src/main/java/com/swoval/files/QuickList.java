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
   * Lists the files and directories in {@code path} following symbolic links. For a symbolic link
   * to a directory, the results will contain the children of the symbolic link target relative to
   * the symbolic link base. For example, if /foo contains a symbolic link called dir-link that
   * links to /bar where /bar contains a file named baz, then the results will include a {@link
   * QuickFile} for /foo/dir-link/baz (provided that the maxDepth >= 1).
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
   * Lists the files and directories in {@code path} following symbolic links. When {@code
   * followLinks} is true, for a symbolic link to a directory, the results will contain the children
   * of the symbolic link target relative to the symbolic link base. For example, if /foo contains a
   * symbolic link called dir-link that links to /bar where /bar contains a file named baz, then the
   * results will include a {@link QuickFile} for /foo/dir-link/baz (provided that the maxDepth >=
   * 1).
   *
   * @param path The path to list
   * @param maxDepth The maximum depth of the file system tree to traverse
   * @param followLinks Toggles whether or not to follow symbolic links in the path
   * @return a List of {@link QuickFile} instances
   * @throws IOException when the path isn't listable because it isn't a directory, access is denied
   *     or the path doesn't exist. May also throw due to any io error.
   */
  public static List<QuickFile> list(final Path path, final int maxDepth, final boolean followLinks)
      throws IOException {
    return INSTANCE.list(path, maxDepth, followLinks);
  }

  /**
   * Lists the files and directories in {@code path}. When {@code followLinks} is true, for a
   * symbolic link to a directory, the results will contain the children of the symbolic link target
   * relative to the symbolic link base. For example, if /foo contains a symbolic link called
   * dir-link that links to /bar where /bar contains a file named baz, then the results will include
   * a {@link QuickFile} for /foo/dir-link/baz (provided that the maxDepth >= 1). Files and directories
   * that do not pass the {@code filter} are discarded.
   *
   * @param path The path to list
   * @param maxDepth The maximum depth of the file system tree to traverse
   * @param followLinks Toggles whether or not to follow symbolic links in the path
   * @param filter Files passing this function are returned
   * @return a List of {@link QuickFile} instances
   * @throws IOException when the path isn't listable because it isn't a directory, access is denied
   *     or the path doesn't exist. May also throw due to any io error.
   */
  public static List<QuickFile> list(
      final Path path,
      final int maxDepth,
      final boolean followLinks,
      final Filter<? super QuickFile> filter)
      throws IOException {
    return INSTANCE.list(path, maxDepth, followLinks, filter);
  }
}
