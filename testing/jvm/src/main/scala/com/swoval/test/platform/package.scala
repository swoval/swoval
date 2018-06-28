package com.swoval.test

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  DirectoryNotEmptyException,
  FileVisitResult,
  FileVisitor,
  Path,
  Paths,
  Files
}

import scala.concurrent.ExecutionContext

package object platform {
  def executionContext: ExecutionContext = ExecutionContext.global
  def createTempFile(dir: String, prefix: String): String =
    deleteOnExit(Files.createTempFile(Paths.get(dir), prefix, "").toRealPath())

  def createTempDirectory(): String = {
    val base = Paths.get(System.getProperty("java.io.tmpdir")).resolve("swoval")
    Files.createDirectories(base)
    deleteOnExit(Files.createTempDirectory(base, "dir").toRealPath())
  }

  def createTempSubdirectory(dir: String): String =
    deleteOnExit(Files.createTempDirectory(Paths.get(dir), "subdir").toRealPath())

  def delete(dir: String): Unit = {
    Files.walkFileTree(
      Paths.get(dir),
      new FileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          FileVisitResult.CONTINUE
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.deleteIfExists(file)
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
          FileVisitResult.CONTINUE
        }
        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          try {
            Files.deleteIfExists(dir)
          } catch {
            case _: DirectoryNotEmptyException => delete(dir.toString)
          }
          FileVisitResult.CONTINUE
        }
      }
    )
  }
  def mkdir(path: String): String = Files.createDirectories(Paths.get(path)).toRealPath().toString
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
