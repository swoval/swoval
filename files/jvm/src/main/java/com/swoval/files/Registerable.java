package com.swoval.files;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;

/**
 * Augments the {@link java.nio.file.WatchService} with a {@link Registerable#register} method. This
 * is because {@link Path#register(java.nio.file.WatchService, Kind[])} does not work on custom watch services.
 */
public interface Registerable extends java.nio.file.WatchService {
  WatchKey register(final Path path, final WatchEvent.Kind<?>... kinds) throws IOException;
}
