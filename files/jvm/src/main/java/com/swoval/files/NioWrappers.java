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
  private NioWrappers() {}

  static BasicFileAttributes readAttributes(
      final Path path, final com.swoval.files.LinkOption... linkOptions) throws IOException {
    final java.nio.file.LinkOption[] options = new java.nio.file.LinkOption[linkOptions.length];
    for (int i = 0; i < options.length; ++i) {
      options[i] =
          linkOptions[i] == com.swoval.files.LinkOption.NOFOLLOW_LINKS
              ? LinkOption.NOFOLLOW_LINKS
              : null;
    }
    return Files.<BasicFileAttributes>readAttributes(path, BasicFileAttributes.class, options);
  }
}
