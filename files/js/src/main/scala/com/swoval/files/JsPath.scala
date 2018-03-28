package com.swoval.files

import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.path.Path.sep
import io.scalajs.nodejs.path.{ Path => JPath }

class JsPath(val path: String, parent: Option[JsPath] = None) extends Path {
  override lazy val fullName: String =
    parent.map(_.resolve(path).fullName).getOrElse[String](path) match {
      case p if (p startsWith sep) && p != sep =>
        val root = s"$sep${p.split(sep)(1)}"
        JPath.resolve(
          JPath.normalize(
            if (Fs.existsSync(root)) p.replaceAll(root, Fs.realpathSync(root)) else p))
      case p => p
    }

  override lazy val isDirectory: Boolean = exists && Fs.statSync(fullName).isDirectory

  override lazy val name: String = path.toString

  override lazy val parts: Seq[Path] = fullName.split(sep).map(JsPath(_))

  override def createFile(): Path = {
    if (!exists) {
      Fs.closeSync(Fs.openSync(fullName, "w"))
    }
    this
  }

  override def delete(): Boolean = {
    if (exists) {
      if (isDirectory) {
        Fs.readdirSync(fullName) foreach { p =>
          if (Fs.statSync(p).isDirectory) JsPath(p).delete() else Fs.unlinkSync(p)
        }
        Fs.rmdirSync(fullName)
      } else {
        Fs.unlinkSync(fullName)
      }
      true
    } else {
      false
    }
  }

  override def equals(other: Any): Boolean = other match {
    case that: JsPath => this.fullName == that.fullName
    case _            => false
  }

  override def exists: Boolean = Fs.existsSync(fullName)

  override def isAbsolute: Boolean = parent.isEmpty

  override def getParent: Path = {
    JsPath(fullName.lastIndexOf(sep) match {
      case 0 => sep
      case i => fullName.substring(0, i)
    })
  }

  override def getRoot: Path = JsPath("/")

  override def hashCode: Int = fullName.hashCode

  override def lastModified: Long =
    if (exists) Fs.statSync(fullName).mtime.getTime().toLong
    else 0

  override def list(recursive: Boolean, pathFilter: PathFilter): Seq[Path] = {
    if (exists && isDirectory) {
      Fs.readdirSync(fullName).view.map(resolve) flatMap {
        case p if pathFilter(p) =>
          Seq(p) ++ (if (p.isDirectory && recursive) p.list(recursive, pathFilter) else Seq.empty)
        case p => Seq.empty
      }
    } else {
      Seq.empty
    }
  }

  override def mkdir(): Path = {
    if (!exists) Fs.mkdirSync(fullName)
    this
  }

  override def mkdirs(): Path = parts match {
    case Seq(_, t @ _*) if t.lengthCompare(1) >= 1 =>
      t.foldLeft(Path(sep)) { case (a, p) => a.resolve(p).mkdir() }
    case Seq(_, h) => h.mkdir()
  }

  override def normalize: Path = JsPath(JPath.normalize(fullName).replaceAll(s"$sep$$", ""))

  override def relativize(other: Path): Path =
    other.fullName.split(s"$fullName$sep") match {
      case Array(_, r) => JsPath(r)
      case _           => other
    }

  override def renameTo(other: Path): Path = {
    Fs.renameSync(fullName, other.fullName)
    Path(other.fullName)
  }

  def resolve(other: String): Path = resolve(JsPath(other))
  override def resolve(other: Path): Path = {
    if (other.fullName startsWith this.fullName) {
      other
    } else if (other.fullName startsWith sep) {
      null
    } else {
      JsPath(this.fullName, other.fullName)
    }
  }

  override def setLastModifiedTime(millis: Long): Path = {
    if (!exists) createFile()
    val atime = Fs.statSync(fullName).atime.getTime.toInt
    Fs.utimesSync(fullName, atime, (millis / 1000).toInt)
    this
  }

  override def startsWith(other: Path): Boolean = this.fullName startsWith other.fullName

  override def toAbsolute: Path = JsPath(fullName)

  override def toString = s"JsPath($fullName)"

  override def write(content: String): Unit = {
    val fd = Fs.openSync(fullName, "a+")
    Fs.writeSync(fd, content)
    Fs.closeSync(fd)
  }
}

object JsPath {
  def apply(parts: String*): Path = new JsPath(parts mkString sep, None)
  def relative(parent: JsPath, child: String): JsPath =
    new JsPath(child, Some(parent))
}
