package com.swoval.test

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, FileVisitor, Path, Paths, Files => JFiles }

import scala.concurrent.ExecutionContext

package object platform {
  def executionContext: ExecutionContext = ExecutionContext.global
  def createTempFile(dir: String, prefix: String): String =
    deleteOnExit(JFiles.createTempFile(Paths.get(dir), prefix, "").toRealPath())

  def createTempDirectory(): String =
    deleteOnExit(JFiles.createTempDirectory("dir").toRealPath())

  def createTempSubdirectory(dir: String): String =
    deleteOnExit(JFiles.createTempDirectory(Paths.get(dir), "subdir").toRealPath())

  def delete(dir: String): Unit = {
    JFiles.walkFileTree(
      Paths.get(dir),
      new FileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          FileVisitResult.CONTINUE
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          JFiles.deleteIfExists(file)
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
          FileVisitResult.CONTINUE
        }
        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          JFiles.deleteIfExists(dir)
          FileVisitResult.CONTINUE
        }
      }
    )
  }
  def mkdir(path: String): String = JFiles.createDirectories(Paths.get(path)).toRealPath().toString
  private def deleteOnExit(path: Path): String = {
    Runtime
      .getRuntime()
      .addShutdownHook(new Thread() {
        override def run(): Unit = {
          delete(path.toString)
        }
      })
    path.toString
  }

}
