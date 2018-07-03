package com.swoval.runtime;

import com.swoval.files.QuickLister;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Provides a static method for loading the swoval-files0 native library. This library provides a
 * native implementation of {@link QuickLister}. On OSX, it also provides a native interface to the
 * apple file system api. The loader extracts the packaged library (.so, .dll or .dylib) to a
 * temporary directory and loads it from there. On jvm shutdown, it unloads the library
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
              if (file.toString().endsWith(".delete")) {
                Files.deleteIfExists(Paths.get(file.toString().replaceAll(".delete", "")));
                Files.deleteIfExists(file);
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
    final URL url = NativeLoader.class.getResource(resourcePath);
    if (url == null) {
      String msg = "Native library " + lib + " (" + resourcePath + ") can't be loaded.";
      throw new UnsatisfiedLinkError(msg);
    }
    final Path extractedPath = Files.createTempFile(swoval, "", "-" + lib);
    try {
      Files.write(extractedPath, Files.readAllBytes(Paths.get(url.toURI())));
      System.load(extractedPath.toString());
      ShutdownHooks.addHook(
          2,
          new Runnable() {
            public void run() {
              try {
                Files.createFile(
                    swoval.resolve(extractedPath.getFileName().toString() + ".delete"));
              } catch (IOException e) {
                System.err.println("Error marking " + extractedPath + " for deletion: " + e);
              }
            }
          });

    } catch (final URISyntaxException e) {
      throw new RuntimeException(e); // Should be unreachable
    } catch (final UnsatisfiedLinkError e) {

      Files.deleteIfExists(extractedPath);
      throw e;
    }
    final Thread thread =
        new Thread("com.swoval.runtime.NativeLoader.cleanupThread") {
          @Override
          public void run() {
            cleanupTask(swoval);
          }
        };
    thread.setDaemon(true);
    thread.start();
  }

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
