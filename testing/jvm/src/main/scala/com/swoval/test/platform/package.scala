package com.swoval.test

import java.nio.file.{ AccessDeniedException, NoSuchFileException, Path, Paths, Files => JFiles }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.Try

package object platform {
  def executionContext: ExecutionContext = ExecutionContext.global
  def createTempFile(dir: String, prefix: String): String =
    deleteOnExit(JFiles.createTempFile(Paths.get(dir), prefix, "").toRealPath())

  def createTempDirectory(): String =
    deleteOnExit(JFiles.createTempDirectory("dir").toRealPath())

  def createTempSubdirectory(dir: String): String =
    deleteOnExit(JFiles.createTempDirectory(Paths.get(dir), "subdir").toRealPath())

  def delete(dir: String): Unit = {
    def list(p: Path): Seq[Path] =
      try {
        val stream = JFiles.list(p.toRealPath())
        try stream.iterator.asScala.toIndexedSeq
        finally stream.close()
      } catch {
        case _: NoSuchFileException | _: AccessDeniedException => Nil
      }

    @tailrec
    def impl(allFiles: Seq[Path], directoriesToDelete: Seq[Path]): Unit = {
      val (files, dirs) = allFiles.partition(JFiles.isRegularFile(_))
      files.foreach(f => Try(JFiles.deleteIfExists(f)))
      dirs match {
        case l if l.isEmpty => directoriesToDelete.foreach(f => Try(JFiles.deleteIfExists(f)))
        case l: Seq[Path]   => impl(l.flatMap(list), l ++ directoriesToDelete)
      }
    }
    val path = Paths.get(dir)
    if (JFiles.isDirectory(path)) impl(list(path), Seq(path)) else Try(JFiles.deleteIfExists(path))
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
