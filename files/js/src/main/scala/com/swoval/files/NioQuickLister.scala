package com.swoval.files

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.HashSet
import java.util.Set
import java.util.concurrent.atomic.AtomicReference

class NioQuickLister extends QuickListerImpl {

  protected override def listDir(dir: String, followLinks: Boolean): QuickListerImpl.ListResults = {
    val basePath: Path = Paths.get(dir)
    val results: QuickListerImpl.ListResults =
      new QuickListerImpl.ListResults()
    val linkOptions: Set[FileVisitOption] = new HashSet[FileVisitOption]()
    if (followLinks) linkOptions.add(FileVisitOption.FOLLOW_LINKS)
    val exception: AtomicReference[IOException] =
      new AtomicReference[IOException]()
    Files.walkFileTree(
      basePath,
      linkOptions,
      1,
      new FileVisitor[Path]() {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          FileVisitResult.CONTINUE

        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (attrs.isDirectory) {
            results.addDir(file.getFileName.toString)
          } else if (attrs.isSymbolicLink && followLinks && Files.isDirectory(file.toRealPath())) {
            results.addDir(file.getFileName.toString)
          } else if (file == basePath) {
            throw new NotDirectoryException(dir)
          } else {
            results.addFile(file.getFileName.toString)
          }
          FileVisitResult.CONTINUE
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
    results
  }

}
