package com.swoval.files;

import com.swoval.runtime.NativeLoader;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

class NativeDirectoryLister implements DirectoryLister {
  public NativeDirectoryLister() {}

  static final int UNKNOWN = SimpleFileTreeView.UNKNOWN;
  static final int DIRECTORY = SimpleFileTreeView.DIRECTORY;
  static final int FILE = SimpleFileTreeView.FILE;
  static final int LINK = SimpleFileTreeView.LINK;
  static final int EOF = 8;
  static final int ENOENT = -1;
  static final int EACCES = -2;
  static final int ENOTDIR = -3;
  static final int ESUCCESS = -4;

  static {
    try {
      NativeLoader.loadPackaged();
    } catch (IOException | UnsatisfiedLinkError e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public SimpleFileTreeView.ListResults apply(final String dir, final boolean followLinks)
      throws IOException {
    return fillResults(dir, followLinks, 0);
  }

  private native int errno(long handle);

  private native String strerror(int error);

  private native long openDir(String dir);

  private native void closeDir(long handle);

  private native long nextFile(long handle);

  private native int getType(long fileHandle);

  private native String getName(long fileHandle);

  @SuppressWarnings("EmptyCatchBlock")
  private SimpleFileTreeView.ListResults fillResults(
      final String dir, final boolean followLinks, final int attempt) throws IOException {
    final SimpleFileTreeView.ListResults results = new SimpleFileTreeView.ListResults();
    final long handle = Platform.isWin() ? openDir(dir + "\\*") : openDir(dir);
    final int err = errno(handle);
    switch (err) {
      case ESUCCESS:
      case EOF:
        break;
      case ENOENT:
        throw new NoSuchFileException(dir);
      case EACCES:
        throw new AccessDeniedException(dir);
      case ENOTDIR:
        throw new NotDirectoryException(dir);
      case 0:
        break;
      default:
        if (Platform.isWin() && attempt < 10) {
          try {
            Thread.sleep(2);
            return fillResults(dir, followLinks, attempt + 1);
          } catch (final InterruptedException e) {
          }
        }
        throw new UnixException(err);
    }
    try {
      long fileHandle = nextFile(handle);
      while (fileHandle != 0) {
        final int fileType = getType(fileHandle);
        switch (fileType) {
          case DIRECTORY:
            results.addDir(getName(fileHandle));
            break;
          case FILE:
            results.addFile(getName(fileHandle));
            break;
          case LINK:
            results.addSymlink(getName(fileHandle));
            break;
          default:
            {
              final Path path = Paths.get(dir);
              final String name = getName(fileHandle);
              final Path file = path.resolve(name);
              final BasicFileAttributes attrs =
                  Files.readAttributes(
                      file, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
              if (attrs.isDirectory()) results.addDir(name);
              else if (attrs.isSymbolicLink()) results.addSymlink(name);
              else results.addFile(name);
            }
            break;
        }
        fileHandle = nextFile(handle);
      }
    } finally {
      closeDir(handle);
    }
    return results;
  }

  class UnixException extends IOException {
    UnixException(int errno) {
      super(strerror(errno));
    }
  }
}
