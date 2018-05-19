package com.swoval.files

import com.swoval.files.PlatformFiles.DurationOps

import scala.concurrent.duration.Duration

object PlatformFiles {
  implicit class DurationOps(val f: Duration) extends AnyVal {
    def sleep = Thread.sleep(f.toMillis)
  }
}
trait PlatformFiles {
  import scala.language.implicitConversions
  implicit def toDurationOps(f: Duration): DurationOps = new DurationOps(f)
}
