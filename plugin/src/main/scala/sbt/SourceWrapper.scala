package sbt

import sbt.internal.io.Source

/*
 * Workaround class because Source.accept is private to package sbt
 */
object SourceWrapper {
  implicit class RichSource(val s: Source) extends AnyVal {
    def accept(path: java.nio.file.Path) = s.accept(path)
  }
}
