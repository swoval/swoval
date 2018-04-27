package com.swoval.test

import java.nio.file.{ NoSuchFileException, Path, Paths, Files => JFiles }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

package object platform {
  def executionContext: ExecutionContext = ExecutionContext.global
  def createTempFile(dir: String, prefix: String): String =
    JFiles.createTempFile(Paths.get(dir), prefix, "").toRealPath().toString

  def createTempDirectory(): String = JFiles.createTempDirectory("dir").toRealPath().toString

  def createTempSubdirectory(dir: String): String = {
    val p = JFiles.createTempDirectory(Paths.get(dir), "subdir").toRealPath()
    p.toString
  }

  def delete(dir: String): Unit = {
    def list(p: Path): Seq[Path] =
      try {
        val stream = JFiles.list(p)
        try stream.iterator.asScala.toIndexedSeq
        finally stream.close()
      } catch {
        case _: NoSuchFileException => Nil
      }

    @tailrec
    def impl(allFiles: Seq[Path], directoriesToDelete: Seq[Path]): Unit = {
      val (files, dirs) = allFiles.partition(JFiles.isRegularFile(_))
      files.foreach(JFiles.deleteIfExists)
      dirs match {
        case l if l.isEmpty => directoriesToDelete.foreach(JFiles.deleteIfExists)
        case l: Seq[Path]   => impl(l.flatMap(list), l ++ directoriesToDelete)
      }
    }
    val path = Paths.get(dir)
    if (JFiles.isDirectory(path)) impl(list(path), Seq(path)) else JFiles.deleteIfExists(path)
  }
  def mkdir(path: String): String = JFiles.createDirectories(Paths.get(path)).toRealPath().toString
}
