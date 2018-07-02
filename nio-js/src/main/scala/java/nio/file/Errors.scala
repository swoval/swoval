package java.nio.file

import java.io.IOException

import scala.scalajs.js
import scala.scalajs.js.JavaScriptException

private[file] object Errors {
  def rethrow(path: Path, exception: Exception): Nothing = exception match {
    case JavaScriptException(ex) =>
      ex.asInstanceOf[js.Dynamic].code.toString match {
        case "ENOTDIR" => throw new NotDirectoryException(path.toString)
        case "ENOENT"  => throw new NoSuchFileException(path.toString)
        case "EACCES"  => throw new AccessDeniedException(path.toString)
        case code      => throw new IOException(s"Error opening file: $path: '$code'")
      }
    case e => throw e
  }
  def wrap[R](path: Path, f: => R): R =
    try {
      f
    } catch { case e: Exception => rethrow(path, e) }
}
