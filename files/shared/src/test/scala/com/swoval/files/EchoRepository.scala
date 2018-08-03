package com.swoval.files
import java.nio.file.Path

import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.TestHelpers._

object EchoRepository {
  def apply(path: Path, depth: Int, followLinks: Boolean = true): FileTreeRepository[Path] = {
    val res = FileTreeRepositories.get[Path](followLinks, (_: TypedPath).getPath)
    res.addObserver(new Observer[Entry[Path]] {
      override def onError(t: Throwable): Unit = {}
      override def onNext(t: Entry[Path]): Unit = println(t)
    })
    res.register(path, depth)
    res
  }
}
