package com.swoval.files.test

import java.nio.file.Files

import com.swoval.files.TypedPath

trait LastModified {
  val lastModified: Long
}
object LastModified {
  def apply(typedPath: TypedPath): LastModified = new LastModified {
    override val lastModified: Long = Files.getLastModifiedTime(typedPath.getPath).toMillis
  }
}
case class FileBytes(bytes: Seq[Byte], override val lastModified: Long) extends LastModified
object FileBytes {
  def apply(p: TypedPath): FileBytes =
    FileBytes(if (p.isDirectory) Seq.empty else Files.readAllBytes(p.getPath).toIndexedSeq,
              Files.getLastModifiedTime(p.getPath).toMillis)
}
