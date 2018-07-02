package java.nio.file

import java.io.File

object Paths {
  def get(first: String, rest: Array[String] = Array.empty[String]): Path = {
    val all = first +: rest
    new JSPath(all.mkString(File.separator))
  }
}
