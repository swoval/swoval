package com.swoval.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Provide wrapper methods for java.nio.file apis that take enum varargs, e.g. {@link
 * java.nio.file.Files#readAttributes(Path, Class, LinkOption...)}. These methods cannot be
 * implemented on the jvm because of linking issues, so this adapter provides a source compatible
 * workaround.
 */
class NioWrappers {
  static BasicFileAttributes readAttributes(
      final Path path, final java.nio.file.LinkOption... linkOptions) throws IOException {
    return Files.<BasicFileAttributes>readAttributes(path, BasicFileAttributes.class, linkOptions);
  }
}
