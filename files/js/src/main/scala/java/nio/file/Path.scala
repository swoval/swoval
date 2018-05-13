package java.nio.file

import java.io.File
import java.net.URI
import java.util

import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.path.{ Path => JPath }

import scala.collection.JavaConverters._

trait Path {
  def endsWith(other: Path): Boolean
  def endsWith(other: String): Boolean
  def getRoot(): Path
  def getFileName(): Path
  def getFileSystem: FileSystem
  def getParent(): Path
  def getNameCount(): Int
  def getName(index: Int): Path
  def isAbsolute(): Boolean
  def subpath(beginIndex: Int, endIndex: Int): Path
  def startsWith(other: Path): Boolean
  def startsWith(other: String): Boolean
  def normalize(): Path
  def register(watcher: WatchService,
               events: Array[WatchEvent.Kind[_]],
               modifiers: Array[WatchEvent.Modifier]): WatchKey
  def register(watcher: WatchService, events: Array[WatchEvent.Kind[_]]): WatchKey
  def resolve(other: Path): Path
  def resolve(other: String): Path
  def resolveSibling(other: Path): Path
  def resolveSibling(other: String): Path
  def relativize(other: Path): Path
  def toUri(): URI
  def toAbsolutePath: Path
  def toRealPath(options: Array[LinkOption] = Array.empty): Path
  def toFile(): File
  def iterator(): util.Iterator[Path]
  def compareTo(other: Path): Int
}

class JSPath(rawPath: String) extends Path {
  val path = rawPath.replaceAll("//", "/").replaceAll("/./", "/").replaceAll("/$", "")
  private lazy val file = new File(path)
  private lazy val parts: Seq[String] = path.split(JPath.sep) match {
    case Array("", rest @ _*) => rest
    case a                    => a
  }
  override def endsWith(other: Path): Boolean = endsWith(other.toString);
  override def endsWith(other: String): Boolean = this.file.getAbsolutePath.endsWith(other)
  override def getRoot(): Path = new JSPath(File.separator)
  override def getFileName(): Path = new JSPath(file.getName)
  override def getFileSystem: FileSystem = ???
  override def getParent(): Path = new JSPath(file.getParent)
  override def getNameCount(): Int = parts.length
  override def getName(index: Int): Path = new JSPath(parts(index))
  override def isAbsolute(): Boolean = file.isAbsolute
  override def subpath(beginIndex: Int, endIndex: Int): Path =
    new JSPath(parts.slice(beginIndex, endIndex).mkString(JPath.sep))
  override def startsWith(other: Path): Boolean = path.toString.startsWith(other.toString)
  override def startsWith(other: String): Boolean = path.toString.startsWith(other)
  override def normalize(): Path = new JSPath(file.getCanonicalPath)
  override def resolve(other: Path): Path = resolve(other.toString)
  override def resolve(other: String): Path = {
    if (other.toString.startsWith(this.toString) || other.toString.startsWith(JPath.sep)) {
      new JSPath(other)
    } else {
      new JSPath(toString() + JPath.sep + other)
    }
  }
  override def resolveSibling(other: Path): Path = ???
  override def resolveSibling(other: String): Path = ???
  override def relativize(other: Path): Path = {
    other.toString.split(s"$this(${JPath.sep}?)") match {
      case Array("", r) => new JSPath(r)
      case Array()      => new JSPath("")
      case res =>
        if (other.isAbsolute && this.isAbsolute) {
          new JSPath(
            (parts.map(_ => "..") ++ other.iterator().asScala.toSeq).mkString(File.separator))
        } else {
          other
        }

    }
  }
  override def toUri(): URI = file.toURI
  override def toAbsolutePath: Path = if (isAbsolute()) this else new JSPath(file.getAbsolutePath)
  override def toRealPath(options: Array[LinkOption]): Path =
    new JSPath(Fs.realpathSync(path))
  override def toFile(): File = file
  override def register(watcher: WatchService,
                        events: Array[WatchEvent.Kind[_]],
                        modifiers: Array[WatchEvent.Modifier]): WatchKey = ???
  override def register(watcher: WatchService, events: Array[WatchEvent.Kind[_]]): WatchKey = ???
  override def iterator(): util.Iterator[Path] = new util.Iterator[Path] {
    private[this] var i = 0
    override def hasNext: Boolean = i < parts.length
    override def next(): Path = {
      val res = new JSPath(parts(i))
      i += 1
      res
    }
    override def remove(): Unit = {}
  }
  override def compareTo(other: Path): Int = this.path.compareTo(other.toString)
  override def toString(): String = path
  override def hashCode(): Int = path.hashCode()
  override def equals(o: Any): Boolean = o match {
    case that: Path => this.toString == that.toString
    case _          => false
  }
}
