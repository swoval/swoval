package com.swoval.files;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.Vector;

/**
 * Provides a static method for loading the swoval-files0 native library. This library provides a
 * native implementation of {@link QuickLister}. On OSX, it also provides a native interface to the
 * apple file system api. The loader extracts the packaged library (.so, .dll or .dylib) to a
 * temporary directory and loads it from there. On jvm shutdown, it unloads the library
 */
public class NativeLoader {
  private static final String NATIVE_LIBRARY = "swoval-files0";
  private static final String lib = System.mapLibraryName(NATIVE_LIBRARY);
  private static final Field usrPathsField;

  static {
    try {
      usrPathsField = ClassLoader.class.getDeclaredField("usr_paths");
      usrPathsField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void loadPackaged(final String arch) throws IOException {
    final Path temp = Files.createTempDirectory("jni-").toRealPath();
    final String resourcePath = "/native/" + arch + "/" + lib;
    final InputStream resourceStream = NativeLoader.class.getResourceAsStream(resourcePath);
    if (resourceStream == null) {
      String msg = "Native library " + lib + " (" + resourcePath + ") can't be loaded.";
      deleteRecursive(temp);
      throw new UnsatisfiedLinkError(msg);
    }
    final Path extractedPath = temp.resolve(lib);
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

    try {
      System.load(extractedPath.toString());
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread() {
                public void run() {
                  unloadNativeLibs();
                  try {
                    Files.deleteIfExists(extractedPath);
                    Files.deleteIfExists(temp);
                  } catch (IOException e) {
                    System.err.println("Error deleting temporary directory " + temp);
                  }
                }
              });

    } catch (UnsatisfiedLinkError e) {
      deleteRecursive(temp);
      throw e;
    }
  }

  /**
   * This is necessary to unload the native shared library on windows. Windows won't let you delete
   * a dll if it is in use, so it's necessary to unload it which is possible using by calling
   * finalize on the {@link ClassLoader.NativeLibrary} objects.
   */
  @SuppressWarnings({"unchecked", "EmptyCatchBlock"})
  private static void unloadNativeLibs() {
    try {
      ClassLoader classLoader = NativeLoader.class.getClassLoader();
      Field field = ClassLoader.class.getDeclaredField("nativeLibraries");
      field.setAccessible(true);
      Vector<Object> libs = (Vector<Object>) field.get(classLoader);
      Iterator it = libs.iterator();
      while (it.hasNext()) {
        Object object = it.next();
        Field[] fs = object.getClass().getDeclaredFields();
        for (int k = 0; k < fs.length; k++) {
          if (fs[k].getName().equals("name")) {
            fs[k].setAccessible(true);
            Method finalize = object.getClass().getDeclaredMethod("finalize");
            finalize.setAccessible(true);
            finalize.invoke(object);
          }
        }
      }
    } catch (Exception e) {
    }
  }

  private static void deleteRecursive(final Path path) {
    try {
      Files.walkFileTree(
          path,
          new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      System.err.println("Error deleting " + path + ": " + e);
    }
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
