package com.swoval.files

import java.nio.file.Path
import java.util.List

import com.swoval.functional.Filter

object QuickList {
  private val INSTANCE: QuickLister =
    if (Platform.isJVM) new NativeQuickLister() else new NioQuickLister()

  def list(path: Path, maxDepth: Int): List[QuickFile] =
    INSTANCE.list(path, maxDepth, followLinks = true)

  def list(path: Path, maxDepth: Int, followLinks: Boolean): List[QuickFile] =
    INSTANCE.list(path, maxDepth, followLinks)

  def list(path: Path,
           maxDepth: Int,
           followLinks: Boolean,
           filter: Filter[_ >: QuickFile]): List[QuickFile] =
    INSTANCE.list(path, maxDepth, followLinks, filter)
}
