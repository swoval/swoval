package com.swoval.files.test

import java.nio.file.{ Files, Path }

trait LastModified {
  val lastModified: Long
}
object LastModified {
  def apply(path: Path): LastModified = new LastModified {
    override val lastModified: Long = Files.getLastModifiedTime(path).toMillis
  }
}
case class FileBytes(bytes: Seq[Byte], override val lastModified: Long) extends LastModified
object FileBytes {
  def apply(p: Path): FileBytes =
    FileBytes(if (Files.isDirectory(p)) Seq.empty else Files.readAllBytes(p).toIndexedSeq,
              Files.getLastModifiedTime(p).toMillis)
}
