package com.swoval.code

import java.io.File
import java.nio.file.{ Files, Path, Paths }

import com.mysema.scalagen.{ Converter => SConverter }

import scala.collection.JavaConverters._

object Converter {
  import scala.language.implicitConversions
  val link = "(?s)(.+?)\\{@link(?:\n[^*]+?\\*)?[ ]+?([^}]+?)}"
  private val Link = link.r
  private val StripArgs =
    "(.+?)\\[\\[([^#(\\]]+)?(?=[#(])[#(\\]]((?:[^(]+)?(?=[(])|(?:[^\\]]+)(?=[\\]]))[^\\]]*\\]\\](.*)".r
  private val syncRegex = "synchronized\\(([^)]+)\\)".r
  private val paramRegex = "@param [<]([^>]+)[>]".r
  implicit class LineOps(val line: String) extends AnyVal {
    def include: Boolean = {
      line != "//remove if not needed" &&
      line != "import scala.collection.JavaConversions._" &&
      !line.contains("* @throws")
    }
    def fixSynchronization: String =
      syncRegex.replaceAllIn(line, "$1.synchronized")

    def fixRefs: String = line match {
      case Link(prefix, name, params, suffix) => s"$prefix[[$name]]$suffix"
      case l                                  => l
    }
    def fixTypeParams: String = paramRegex.replaceAllIn(line, "@tparam $1")
  }
  def applyAll(content: String, mods: (String => String)*): String = mods.foldLeft(content) {
    (c, mod) =>
      mod(c)
  }
  private val mods = Seq[String => String](
    Link.replaceAllIn(_, "$1[[$2]]"),
    "(?s)^(.*class[^\n]+(?= \\/\\*\\*))(.+?)(?=\\*\\/)\\*\\/".r.replaceAllIn(_: String, "$1"),
    "\\[\\[\\.".r.replaceAllIn(_: String, "[["),
    "(?s)([ ]*\\/\\*\\*.+?\\*\\/)(.*)\\1".r.replaceAllIn(_: String, "$1"),
    "(?s)(.+?import[^\n]+?)\n\nimport".r.replaceAllIn(_: String, "$1\nimport"),
    "(?s)(.+?import[^\n]+?)\n\nimport".r.replaceAllIn(_: String, "$1\nimport"),
    """(?s)(.+?)<a.+?href=["]([^"]+)["][^>]*>""".r.replaceAllIn(_: String, "$1[[$2 "),
    "</a>".r.replaceAllIn(_: String, "]]"),
    "java.lang.Boolean".r.replaceAllIn(_: String, "Boolean")
  )
  def sanitize(lines: Seq[String]): Seq[String] = {
    var original = applyAll(lines.mkString("\n"), mods: _*).lines.toIndexedSeq

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
    val regex = "^[ ]+(class|object) (\\w+Impl|FileOps|EntryFilters)".r
    next.view.map(l => regex.replaceAllIn(l, "private[files] $1 $2"))
  }
  def sanitize(path: Path): String = {
    val lines = Files.readAllLines(path).asScala.flatMap { l =>
      if (l.include) Some(l.fixSynchronization.fixTypeParams) else None
    }
    val varargsRegex = "(.*)options[)]".r
    val newLines = sanitize(lines)
    val fileName = path.getFileName.toString.dropRight(6)
    val needAnyRef =
      Set(
        "CachedDirectory",
        "CachedDirectoryImpl",
        "DirectoryDataView",
        "FileCachePathWatcher",
        "FileCacheDirectoryTree",
        "FileCacheDirectories",
        "FileTreeDataView",
        "FileTreeDataViews",
        "FileTreeRepositories",
        "FileTreeRepository",
        "FileTreeRepositoryImpl",
        "FileTreeViews",
        "UpdatableFileTreeDataView"
      )
    (if (fileName.contains("PathWatcher")) {
       val regex =
         s"(def (?:get|apply|cached)|trait (?:${needAnyRef.mkString("|")})|class (?:${needAnyRef
           .mkString("|")}}))[\\[]T".r
       val contraRegex = "^(.*result: List).R".r
       val longRegex = "([^.])Long".r
       val res = newLines.view
         .filterNot(_.contains("import Entry._"))
         .map(regex.replaceAllIn(_, "$1[T <: AnyRef"))
         .map(contraRegex.replaceAllIn(_, "$1[_ >: R"))
         .filterNot(l => l.contains("import Event._") || l.contains("import Kind._"))
         .map(varargsRegex.replaceAllIn(_, "$1options:_*)"))
       res.map(longRegex.replaceAllIn(_, "$1java.lang.Long"))
     } else if (fileName == "Either") {
       val regex = "class Either\\[L, R\\]".r
       newLines.map(regex.replaceAllIn(_, "class Either[+L, +R]"))
     } else if (needAnyRef.contains(fileName)) {
       val regex =
         s"(def (?:get|apply|cached(?:Updatable)?)|trait (?:${needAnyRef.mkString("|")})|class (?:${needAnyRef
           .mkString("|")}}))[\\[]T".r
       val contraRegex = "^(.*result: List).R".r
       newLines.view
         .filterNot(_.contains("import Entry._"))
         .map(regex.replaceAllIn(_, "$1[T <: AnyRef"))
         .map(contraRegex.replaceAllIn(_, "$1[_ >: R"))
     } else if (fileName.contains("FileCache")) {
       val applyRegex = "(get|class FileCache(?:Impl)?)[\\[]T".r
       newLines.view
         .filterNot(_.contains("import Entry._"))
         .map(applyRegex.replaceAllIn(_, "$1[T <: AnyRef"))
         .map(varargsRegex.replaceAllIn(_, "$1options:_*)"))
     } else if (fileName == "Filters") {
       val regex = "\\[(_ <: )?(Any|_)\\]".r
       newLines.map(regex.replaceAllIn(_, "[$1AnyRef]"))
     } else {
       newLines
     }).mkString("\n")
  }
  def main(args: Array[String]): Unit = {
    val files = args.dropRight(1)
    val targetDir = Paths.get(args.last)
    files.map(new File(_)) foreach { f =>
      new Thread() {
        override def run() {
          val converted = f.toPath.getFileName.toString.replaceAll("\\.java$", ".scala")
          val target = targetDir.resolve(converted).toFile
          print(s"Converting $f to $target...")
          SConverter.instance211.convertFile(f, target)
          println("done")
          print(s"Sanitizing $target ...")
          val warning = "// Do not edit this file manually. It is autogenerated."
          Files.write(target.toPath, s"$warning\n\n${sanitize(target.toPath)}".getBytes)
          println("done.")
        }
      }.start()
    }
  }
}
