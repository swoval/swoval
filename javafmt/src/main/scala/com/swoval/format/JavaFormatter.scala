package com.swoval.format

import java.nio.file.{ Files, Paths }

import com.google.googlejavaformat.java.Formatter
import scala.collection.JavaConverters._

object JavaFormatter {
  def main(args: Array[String]): Unit = {
    val base = Paths.get(args.head)
    val formatter = new Formatter()
    Files
      .walk(base)
      .iterator
      .asScala
      .filter(_.toString.endsWith(".java")) foreach { f =>
      val formatted = formatter.formatSource(new String(Files.readAllBytes(f)))
      Files.write(f, formatted.getBytes)
    }
  }
}
