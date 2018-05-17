package com.swoval.files;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;

public interface Registerable extends java.nio.file.WatchService {
  WatchKey register(final Path path, final WatchEvent.Kind<?>... kinds) throws IOException;
}
