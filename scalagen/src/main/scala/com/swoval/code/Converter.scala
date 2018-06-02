package com.swoval.code

import java.io.File
import java.nio.file.{ Files, Path, Paths }
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import com.mysema.scalagen.{ Converter => SConverter }

object Converter {
  import scala.language.implicitConversions
  val link = "(?s)(.+?)\\{@link(?:\n[^*]+?\\*)?[ ]+?([^}]+?)}"
  private val Link = link.r
  private val StripArgs =
    "(.+?)\\[\\[([^#(\\]]+)?(?=[#(])[#(\\]]((?:[^(]+)?(?=[(])|(?:[^\\]]+)(?=[\\]]))[^\\]]*\\]\\](.*)".r
  implicit class LineOps(val line: String) extends AnyVal {
    def include: Boolean = {
      line != "//remove if not needed" &&
      line != "import scala.collection.JavaConversions._" &&
      !line.contains("* @throws")
    }
    def fixSynchronization: String =
      line.replaceAll("synchronized\\(([^)]+)\\)", "$1.synchronized")

    def fixRefs: String = line match {
      case Link(prefix, name, params, suffix) => s"$prefix[[$name]]$suffix"
      case l                                  => l
    }
    def fixTypeParams: String = line.replaceAll("@param [<]([^>]+)[>]", "@tparam $1")
  }
  def sanitize(lines: Seq[String]): Seq[String] = {
    var original = Link
      .replaceAllIn(lines.mkString("\n"), "$1[[$2]]")
      .replaceAll("(?s)^(.*class[^\n]+(?= \\/\\*\\*))(.+?)(?=\\*\\/)\\*\\/", "$1")
      .replaceAll("\\[\\[\\.", "[[")
      .replaceAll("(?s)([ ]*\\/\\*\\*.+?\\*\\/)(.*)\\1", "$1")
      .replaceAll("(?s)(.+?import[^\n]+?)\n\nimport", "$1\nimport")
      .replaceAll("(?s)(.+?import[^\n]+?)\n\nimport", "$1\nimport")
      .replaceAll("""(?s)(.+?)<a.+?href=["]([^"]+)["][^>]*>""", "$1[[$2 ")
      .replaceAll("</a>", "]]")
      .lines
      .toIndexedSeq

    var next = original
    do {
      original = next
      next = next.map {
        case StripArgs(prefix, null, method, rest)      => s"$prefix[[$method]]$rest"
        case StripArgs(prefix, qualifier, null, rest)   => s"$prefix[[$qualifier]]$rest"
        case StripArgs(prefix, qualifier, method, rest) => s"$prefix[[$qualifier.$method]]$rest"
        case l                                          => l
      }
    } while (original != next)
    next.map(
      l =>
        l.replaceAll("(class|object) (\\w+Impl|Observers|FileOps|EntryFilters)",
                     "private[files] $1 $2"))
  }
  def sanitize(path: Path): String = {
    val lines = Files.readAllLines(path).asScala.flatMap { l =>
      if (l.include) Some(l.fixSynchronization.fixTypeParams) else None
    }
    val newLines = sanitize(lines)
    (if (path.toString.contains("DirectoryWatcher.scala")) {
       newLines.filterNot(_.contains("import Event._"))
     } else if (path.toString.contains("Directory.scala")) {
       newLines.filterNot(_.contains("import Entry._"))
     } else if (path.toString.contains("FileCache.scala")) {
       newLines.map(_.replaceAll("(new FileCacheImpl.*)options", "$1options:_*"))
     } else {
       newLines
     }).mkString("\n")
  }
  def main(args: Array[String]): Unit = {
    val files = args.dropRight(1)
    val targetDir = Paths.get(args.last)
    files.map(new File(_)) foreach { f =>
      val converted = f.toPath.getFileName.toString.replaceAll("\\.java$", ".scala")
      val target = targetDir.resolve(converted).toFile
      print(s"Converting $f to $target...")
      SConverter.instance211.convertFile(f, target)
      Files.write(target.toPath, sanitize(target.toPath).getBytes)
      println("done.")
    }
  }
}
