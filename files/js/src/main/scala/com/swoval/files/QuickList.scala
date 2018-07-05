package com.swoval.files

import java.nio.file.Path
import java.util.List

import com.swoval.functional.Filter
import com.swoval.runtime.Platform

/**
 * Provides a fast method [[QuickList.list]] for listing the files in a directory. The js
 * implementation does not use native code like the jvm version yet. This could be optimized
 * down the road.
 */
object QuickList {
  private val INSTANCE: QuickLister = QuickListers.getNio()

  /**
   * Lists the files and directories in {{{path}}}. When {{{followLinks}}} is true, for a
   * symbolic link to a directory, the results will contain the children of the symbolic link target
   * relative to the symbolic link base. For example, if /foo contains a symbolic link called
   * dir-link that links to /bar where /bar contains a file named baz, then the results will include
   * a [[QuickFile]] for /foo/dir-link/baz (provided that the {{{maxDepth >= 1}}}). Files and
   * directories that do not pass the {{{filter}}} are discarded.
   *
   * @param path The path to list
   * @param maxDepth The maximum depth of the file system tree to traverse
   * @return a List of [[QuickFile]] instances or the path doesn't exist. May also throw due to any io error.
   */
  def list(path: Path, maxDepth: Int): List[QuickFile] =
    INSTANCE.list(path, maxDepth, followLinks = true)

  /**
   * Lists the files and directories in {{{path}}}. When {{{followLinks}}} is true, for a
   * symbolic link to a directory, the results will contain the children of the symbolic link target
   * relative to the symbolic link base. For example, if /foo contains a symbolic link called
   * dir-link that links to /bar where /bar contains a file named baz, then the results will include
   * a [[QuickFile]] for /foo/dir-link/baz (provided that the {{{maxDepth >= 1}}}). Files and
   * directories that do not pass the {{{filter}}} are discarded.
   *
   * @param path The path to list
   * @param maxDepth The maximum depth of the file system tree to traverse
   * @param followLinks Toggles whether or not to follow symbolic links in the path
   * @return a List of [[QuickFile]] instances or the path doesn't exist. May also throw due to any io error.
   */
  def list(path: Path, maxDepth: Int, followLinks: Boolean): List[QuickFile] =
    INSTANCE.list(path, maxDepth, followLinks)

  /**
   * Lists the files and directories in {{{path}}}. When {{{followLinks}}} is true, for a
   * symbolic link to a directory, the results will contain the children of the symbolic link target
   * relative to the symbolic link base. For example, if /foo contains a symbolic link called
   * dir-link that links to /bar where /bar contains a file named baz, then the results will include
   * a [[QuickFile]] for /foo/dir-link/baz (provided that the {{{maxDepth >= 1}}}). Files and
   * directories that do not pass the {{{filter}}} are discarded.
   *
   * @param path The path to list
   * @param maxDepth The maximum depth of the file system tree to traverse
   * @param followLinks Toggles whether or not to follow symbolic links in the path
   * @param filter Files passing this function are returned
   * @return a List of [[QuickFile]] instances or the path doesn't exist. May also throw due to any io error.
   */
  def list(path: Path,
           maxDepth: Int,
           followLinks: Boolean,
           filter: Filter[_ >: QuickFile]): List[QuickFile] =
    INSTANCE.list(path, maxDepth, followLinks, filter)
}
