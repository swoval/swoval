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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

  private static class Retry extends IOException {}

  private static final int MAX_ATTEMPTS = 100;

  @Override
  public SimpleFileTreeView.ListResults apply(final String dir, final boolean followLinks)
      throws IOException {
    int attempt = 0;
    while (attempt < MAX_ATTEMPTS) {
      try {
        return fillResults(dir);
      } catch (final Retry retry) {
        try {
          Thread.sleep(0, 200);
        } catch (final InterruptedException e) {
          attempt = MAX_ATTEMPTS;
        }
        attempt += 1;
      }
    }
    throw new NoSuchFileException(dir);
  }

  private native int errno(long handle);

  private native String strerror(int error);

  private native long openDir(String dir);

  private native void closeDir(long handle);

  private native long nextFile(long handle);

  private native int getType(long fileHandle);

  private native String getName(long fileHandle);

  private void close(final long handle, final IOException e) throws IOException {
    if (Platform.isWin()) closeDir(handle);
    throw e;
  }

  @SuppressWarnings("EmptyCatchBlock")
  private SimpleFileTreeView.ListResults fillResults(final String dir) throws IOException {
    final SimpleFileTreeView.ListResults results = new SimpleFileTreeView.ListResults();
    final List<String> unresolved = new ArrayList<>();
    final long handle = Platform.isWin() ? openDir(dir + "\\*") : openDir(dir);
    final int err = errno(handle);
    switch (err) {
      case ESUCCESS:
      case EOF:
        break;
      case ENOENT:
        {
          final boolean retry = Platform.isWin() && Files.isDirectory(Paths.get(dir));
          final IOException e = retry ? new Retry() : new NoSuchFileException(dir);
          close(handle, e);
        }
      case EACCES:
        close(handle, new AccessDeniedException(dir));
      case ENOTDIR:
        close(handle, new NotDirectoryException(dir));
      case 0:
        break;
      default:
        {
          final boolean retry = Platform.isWin() && Files.isDirectory(Paths.get(dir));
          final IOException e = retry ? new Retry() : new UnixException(err);
          close(handle, e);
        }
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
            unresolved.add(getName(fileHandle));
            break;
        }
        fileHandle = nextFile(handle);
      }
    } finally {
      closeDir(handle);
    }

    if (!unresolved.isEmpty()) {
      final Path path = Paths.get(dir);
      final Iterator<String> it = unresolved.iterator();
      while (it.hasNext()) {
        final String name = it.next();
        final Path file = path.resolve(name);
        try {
          final BasicFileAttributes attrs =
              Files.readAttributes(
                  file, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
          if (attrs.isDirectory()) results.addDir(name);
          else if (attrs.isSymbolicLink()) results.addSymlink(name);
          else results.addFile(name);
        } catch (final IOException e) {
        }
      }
    }
    return results;
  }

  class UnixException extends IOException {
    UnixException(int errno) {
      super(strerror(errno));
    }
  }
}
