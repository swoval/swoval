package com.swoval.files.test

import java.nio.file.{ Files, Path => JPath }

import com.swoval.files.Directory.Entry

trait LastModified {
  val lastModified: Long
}
object LastModified {
  def apply(path: JPath): LastModified = new LastModified {
    override val lastModified: Long = Files.getLastModifiedTime(path).toMillis
  }
}
case class FileBytes(bytes: Seq[Byte], override val lastModified: Long) extends LastModified
object FileBytes {
  def apply(p: JPath): FileBytes =
    FileBytes(if (Files.isDirectory(p)) Seq.empty else Files.readAllBytes(p).toIndexedSeq,
              Files.getLastModifiedTime(p).toMillis)
}
