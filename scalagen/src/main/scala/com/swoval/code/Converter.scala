package com.swoval.code

import java.io.File
import java.nio.file.Paths

import com.mysema.scalagen.{ Converter => SConverter }

object Converter {
  def main(args: Array[String]): Unit = {
    val files = args.dropRight(1)
    val targetDir = Paths.get(args.last)
    files.map(new File(_)) foreach { f =>
      val converted = f.toPath.getFileName.toString.replaceAll("\\.java$", ".scala")
      val target = targetDir.resolve(converted).toFile
      print(s"Converting $f to $target...")
      SConverter.instance211.convertFile(f, target)
      println("done.")
    }
  }
}
