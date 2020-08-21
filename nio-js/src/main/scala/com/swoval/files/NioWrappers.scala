package com.swoval.files

import java.nio.file.Path
import java.nio.file.attribute.{ BasicFileAttributes, BasicFileAttributesImpl }

object NioWrappers {
  def readAttributes[T <: BasicFileAttributes](
      path: Path,
      clazz: Class[T],
      options: com.swoval.files.LinkOption*
  ): T = {
    clazz match {
      case c if classOf[BasicFileAttributes].isAssignableFrom(c) =>
        new BasicFileAttributesImpl(path, options).asInstanceOf[T]
      case _ => ???
    }
  }
  def readAttributes(path: Path, linkOptions: LinkOption*): BasicFileAttributes =
    readAttributes(path, classOf[BasicFileAttributes], linkOptions: _*)
}
