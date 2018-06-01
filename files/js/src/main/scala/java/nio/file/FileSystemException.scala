package java.nio.file

import java.io.IOException

class FileSystemException(file: String, other: String, reason: String) extends IOException(reason) {
  def this(file: String) = this(file, null, null)
  def this(file: String, other: String) = this(file, other, null)
  def getFile(): String = file
  def getOtherFile(): String = other
  def getReason(): String = reason
  override def getMessage(): String = {
    if (file == null && other == null) reason
    else {
      val sb = new StringBuilder()
      if (file != null) sb.append(file);
      if (other != null) {
        sb.append(" -> ")
        sb.append(other)
      }
      if (reason != null) {
        sb.append(": ")
        sb.append(getReason())
      }
      return sb.toString()
    }
  }
}
