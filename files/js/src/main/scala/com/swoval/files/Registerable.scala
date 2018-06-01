package com.swoval.files

import java.io.IOException
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchEvent.Kind
import java.nio.file.WatchKey

/**
 * Augments the [[java.nio.file.WatchService]] with a [[Registerable.registe]] method. This
 * is because [[Path#register(java.nio.file.WatchService, Kind[])]] does not work on custom watch services.
 */
trait Registerable extends java.nio.file.WatchService {

  def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey

}
