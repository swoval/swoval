package com.swoval.files

import java.nio.file.{ Files => JFiles, Paths => JPaths }

package object platform {
  def makeExecutor(name: String): Executor = ExecutorServiceWrapper.make(name)
  object pathCompanion extends PathCompanion {
    def apply(parts: String*): Path = JvmPath.apply(parts: _*)
    def createTempFile(dir: Path, prefix: String): Path =
      JvmPath(JFiles.createTempFile(JPaths.get(dir.name), prefix, "").toRealPath())

    def createTempDirectory(): Path = JvmPath(JFiles.createTempDirectory("dir").toRealPath())

    def createTempDirectory(dir: Path, prefix: String): Path =
      JvmPath(JFiles.createTempDirectory(JPaths.get(dir.name), prefix).toRealPath())
  }
}
