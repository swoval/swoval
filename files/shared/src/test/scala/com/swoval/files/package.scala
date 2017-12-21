package com.swoval

import utest._

package object files {
  implicit class RichSwovalPath(val path: Path) extends AnyVal {
    def ===(other: Path): Unit = {
      if (path.normalize != other.normalize) {
        path.normalize ==> other.normalize
      }
    }
  }
}
