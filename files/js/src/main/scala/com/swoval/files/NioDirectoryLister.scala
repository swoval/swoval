// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.{ HashSet, Set }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

class NioDirectoryLister extends DirectoryLister {

  override def apply(dir: String, followLinks: Boolean): SimpleFileTreeView.ListResults = {
    val basePath: Path = Paths.get(dir)
    val results: SimpleFileTreeView.ListResults =
      new SimpleFileTreeView.ListResults()
    val linkOptions: Set[FileVisitOption] = new HashSet[FileVisitOption]()
    val exception: AtomicReference[IOException] =
      new AtomicReference[IOException]()
    val isSymlink: AtomicBoolean = new AtomicBoolean(false)
    Files.walkFileTree(
      basePath,
      linkOptions,
      1,
      new FileVisitor[Path]() {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          FileVisitResult.CONTINUE

        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (attrs.isSymbolicLink) {
            if (file == basePath) {
              isSymlink.set(true)
            } else {
              results.addSymlink(file.getFileName.toString)
            }
          } else if (attrs.isDirectory) {
            results.addDir(file.getFileName.toString)
          } else if (file == basePath) {
            throw new NotDirectoryException(dir)
          } else {
            results.addFile(file.getFileName.toString)
          }
          if (isSymlink.get) FileVisitResult.TERMINATE
          else FileVisitResult.CONTINUE
        }

        override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
          exception.set(exc)
          FileVisitResult.TERMINATE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult =
          FileVisitResult.CONTINUE
      }
    )
    val ex: IOException = exception.get
    if (ex.isInstanceOf[FileSystemException]) {
      val fse: FileSystemException = ex.asInstanceOf[FileSystemException]
      if (ex.getMessage.contains("Not a directory"))
        throw new NotDirectoryException(fse.getFile)
      else throw fse
    } else if (ex != null) throw ex
    if (isSymlink.get) {
      this.apply(basePath.toRealPath().toString, followLinks)
    }
    results
  }

}
