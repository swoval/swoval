package com.swoval.files;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

class NativeQuickLister extends QuickListerImpl {
  public NativeQuickLister() {}

  private static boolean loaded;

  public static boolean available() {
    return loaded;
  }

  static {
    try {
      NativeLoader.loadPackaged();
      loaded = true;
    } catch (IOException | UnsatisfiedLinkError e) {
      loaded = false;
    }
  }

  @Override
  protected QuickListerImpl.ListResults listDir(final String dir, final boolean followLinks)
      throws IOException {
    return fillResults(dir, followLinks);
  }

  private native int errno(long handle);

  private native String strerror(int error);

  private native long openDir(String dir);

  private native void closeDir(long handle);

  private native long nextFile(long handle);

  private native int getType(long fileHandle);

  private native String getName(long fileHandle);

  private QuickListerImpl.ListResults fillResults(final String dir, final boolean followLinks)
      throws IOException {
    final QuickListerImpl.ListResults results = new QuickListerImpl.ListResults();
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
        System.out.println(err);
        throw new UnixException(err);
    }
    try {
      long fileHandle = nextFile(handle);
      while (fileHandle != 0) {
        final int fileType = getType(fileHandle);
        switch (fileType) {
          case EOF:
            break;
          case DIRECTORY:
            results.addDir(getName(fileHandle));
            break;
          case FILE:
            results.addFile(getName(fileHandle));
            break;
          default:
            final Path path = Paths.get(dir);
            final String name = getName(fileHandle);
            final Path file = path.resolve(name);
            final BasicFileAttributes attrs =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isDirectory()) results.addDir(name);
            else if (attrs.isSymbolicLink() && followLinks) {
              if (Files.isDirectory(file)) results.addDir(name);
              else if (!Files.exists(file)) throw new NoSuchFileException(file.toString());
              else results.addFile(name);
            } else results.addFile(name);
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
