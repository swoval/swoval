package com.swoval.files;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class NioDirectoryLister implements DirectoryLister {
  public NioDirectoryLister() {}

  @Override
  public SimpleFileTreeView.ListResults apply(final String dir, final boolean followLinks)
      throws IOException {
    final Path basePath = Paths.get(dir);
    final SimpleFileTreeView.ListResults results = new SimpleFileTreeView.ListResults();
    final Set<FileVisitOption> linkOptions = new HashSet<>();
    final AtomicReference<IOException> exception = new AtomicReference<>();
    final AtomicBoolean isSymlink = new AtomicBoolean(false);
    Files.walkFileTree(
        basePath,
        linkOptions,
        1,
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink()) {
              if (file.equals(basePath)) {
                isSymlink.set(true);
              } else {
                results.addSymlink(file.getFileName().toString());
              }
            } else if (attrs.isDirectory()) {
              results.addDir(file.getFileName().toString());
            } else if (file.equals(basePath)) {
              throw new NotDirectoryException(dir);
            } else {
              results.addFile(file.getFileName().toString());
            }
            return isSymlink.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            exception.set(exc);
            return FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    final IOException ex = exception.get();
    if (ex instanceof FileSystemException) {
      FileSystemException fse = (FileSystemException) ex;
      if (ex.getMessage().contains("Not a directory"))
        throw new NotDirectoryException(fse.getFile());
      else throw fse;
    } else if (ex != null) throw ex;
    if (isSymlink.get()) {
      return this.apply(basePath.toRealPath().toString(), followLinks);
    }
    return results;
  }
}
