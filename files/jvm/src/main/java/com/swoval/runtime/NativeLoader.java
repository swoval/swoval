package com.swoval.runtime;

import com.swoval.files.FileTreeView;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Manages the loading of the swoval-files0 library. This library provides the native implementation
 * of {@link FileTreeView}. On OSX, it also provides a native interface to the apple file system
 * api. The loader will first try to load the library using System.loadLibrary, which will succeed
 * if the library is present in the (DY)LD_LIBRARY_PATH. Otherwise the loader extracts the packaged
 * library (.so, .dll or .dylib) to a temporary directory and loads it from there. On jvm shutdown,
 * it marks the extracted library for deletion. It will generally be deleted the next time a
 * different jvm loads the library.
 *
 * <p>This class is not intended to be used outside of com.swoval.files, but it doesn't belong in
 * that package, so it has to be public here.
 */
public class NativeLoader {
  private static final String NATIVE_LIBRARY = "swoval-files0";
  private static final String lib = System.mapLibraryName(NATIVE_LIBRARY);

  @SuppressWarnings("EmptyCatchBlock")
  private static void cleanupTask(final Path path) {
    try {
      Files.walkFileTree(
          path,
          new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                final Path dir, final BasicFileAttributes attrs) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                throws IOException {
              if (Platform.isWin()) {
                try {
                  Files.deleteIfExists(file);
                } catch (final IOException e) {
                }
              } else if (file.toString().endsWith(".pid")) {
                final String pid = new String(Files.readAllBytes(file));
                boolean pidExists = true;
                if (ShutdownHooks.isShutdown()) {
                  pidExists = !pid.equals(ShutdownHooks.getPid());
                } else {
                  final Process process = new ProcessBuilder("ps", "-p", pid).start();
                  try {
                    process.waitFor();
                    final InputStreamReader is = new InputStreamReader(process.getInputStream());
                    final BufferedReader reader = new BufferedReader(is);
                    String line = reader.readLine();
                    while (line != null) {
                      pidExists = line.contains(pid + " ");
                      line = reader.readLine();
                    }
                  } catch (final InterruptedException e) {
                  }
                }
                if (!pidExists) {
                  Files.deleteIfExists(Paths.get(file.toString().replaceAll(".pid", "")));
                  Files.deleteIfExists(file);
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              try {
                Files.deleteIfExists(dir);
              } catch (final DirectoryNotEmptyException e) {
              } catch (final IOException e) {
                throw e;
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (final IOException e) {
    }
  }

  private static void loadPackaged(final String arch) throws IOException {
    final Path swoval =
        Files.createDirectories(
            Paths.get(System.getProperty("java.io.tmpdir", "/tmp")).resolve("swoval-jni"));
    final String resourcePath = "/native/" + arch + "/" + lib;
    final InputStream resourceStream = NativeLoader.class.getResourceAsStream(resourcePath);
    if (resourceStream == null) {
      String msg = "Native library " + lib + " (" + resourcePath + ") can't be loaded.";
      throw new UnsatisfiedLinkError(msg);
    }
    final Path extractedPath = Files.createTempFile(swoval, "", "-" + lib);
    OutputStream out = new FileOutputStream(extractedPath.toFile());
    try {
      byte[] buf = new byte[1024];
      int len;
      while ((len = resourceStream.read(buf)) >= 0) {
        out.write(buf, 0, len);
      }
    } catch (Exception e) {
      throw new UnsatisfiedLinkError("Error while extracting native library: " + e);
    } finally {
      resourceStream.close();
      out.close();
    }

    final Runnable cleanupRunnable =
        new Runnable() {
          @Override
          public void run() {
            cleanupTask(swoval);
          }
        };
    try {
      System.load(extractedPath.toString());
      if (!Platform.isWin()) {
        Files.write(
            Paths.get(extractedPath.toString() + ".pid"), ShutdownHooks.getPid().getBytes());
        ShutdownHooks.addHook(1, cleanupRunnable);
      }
    } catch (final UnsatisfiedLinkError e) {
      Files.deleteIfExists(extractedPath);
      throw e;
    }
    final Thread thread =
        new Thread(cleanupRunnable, "com.swoval.runtime.NativeLoader.cleanupThread");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Loads the <code>swoval-files0</code> shared library. It will first try to load the library from
   * the system library path and fallback to extracting a platform-specific jar to a temporary
   * directory.
   *
   * @throws IOException if the packaged native binary cannot be written to a temp directory.
   * @throws UnsatisfiedLinkError if no compatible binary for the platform is present.
   */
  public static void loadPackaged() throws IOException, UnsatisfiedLinkError {
    try {
      System.loadLibrary(NATIVE_LIBRARY);
    } catch (UnsatisfiedLinkError e) {
      try {
        loadPackaged("x86_64");
      } catch (UnsatisfiedLinkError ex) {
        loadPackaged("i686");
      }
    }
  }
}
