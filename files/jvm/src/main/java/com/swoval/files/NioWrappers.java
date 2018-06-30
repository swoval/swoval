package com.swoval.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public class NioWrappers {
  public static BasicFileAttributes readAttributes(
      final Path path, final java.nio.file.LinkOption... linkOptions) throws IOException {
    return Files.<BasicFileAttributes>readAttributes(path, BasicFileAttributes.class, linkOptions);
  }
}
