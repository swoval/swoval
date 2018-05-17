package com.swoval.files

import java.io.IOException
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey

trait Registerable extends java.nio.file.WatchService {

  def register(path: Path, kinds: WatchEvent.Kind[_]*): WatchKey

}
