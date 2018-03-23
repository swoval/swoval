package com.swoval.files

import java.nio.file.attribute.FileTime
import java.nio.file.{ Files => JFiles, Path => JPath, Paths => JPaths }

import scala.collection.JavaConverters._

class JvmPath(val path: JPath, parent: Option[JvmPath] = None) extends Path {
  override lazy val fullName = fullJPath.toString

  override lazy val isDirectory: Boolean = JFiles.isDirectory(fullJPath)

  override lazy val name: String = path.toString

  override lazy val parts: Seq[Path] =
    path.iterator.asScala.map(JvmPath(_)).toIndexedSeq

  override def createFile(): Path =
    if (exists) this else JvmPath(JFiles.createFile(fullJPath))

  override def delete(): Boolean = JFiles.deleteIfExists(path)

  override def equals(other: Any): Boolean = other match {
    case that: JvmPath => this.fullJPath == that.fullJPath
    case _             => false
  }

  override def exists: Boolean = JFiles.exists(fullJPath)

  override def isAbsolute: Boolean = parent.isEmpty

  override def getParent: Path = JvmPath(path.getParent)

  override def getRoot: Path = JvmPath(path.getRoot)

  override def hashCode: Int = fullJPath.hashCode

  override def lastModified: Long =
    if (exists) JFiles.getLastModifiedTime(fullJPath).toMillis
    else 0

  override def list(recursive: Boolean, pathFilter: PathFilter): Seq[Path] = {
    val stream = if (recursive) JFiles.walk(path).filter(_ != path) else JFiles.list(path)
    try {
      stream.iterator.asScala
        .map(p => JvmPath(p))
        .filter(pathFilter)
        .toIndexedSeq
    } finally stream.close()
  }

  override def mkdir(): JvmPath = JvmPath(JFiles.createDirectories(path))

  override def mkdirs(): JvmPath = JvmPath(JFiles.createDirectories(path))

  override def normalize: Path = JvmPath(fullJPath.normalize)

  override def relativize(other: Path): Path = other match {
    case o: JvmPath => new JvmPath(fullJPath.relativize(o.fullJPath), Some(this))
  }

  override def renameTo(other: Path): Path = other match {
    case o: JvmPath => JvmPath(JFiles.move(path, o.path))
  }

  override def resolve(other: Path): Path = other match {
    case o: JvmPath => new JvmPath(fullJPath.resolve(o.path), None)
  }

  override def setLastModifiedTime(millis: Long): Path =
    JvmPath(JFiles.setLastModifiedTime(path, FileTime.fromMillis(millis)))

  override def startsWith(other: Path): Boolean = other match {
    case o: JvmPath => fullJPath.startsWith(o.fullJPath)
  }

  override def toAbsolute: Path = JvmPath(fullJPath)

  override def toString = s"JvmPath($fullName)"

  override def write(content: String): Unit = JFiles.write(path, content.getBytes)

  private lazy val fullJPath: JPath =
    (parent map (_.path.resolve(path.toString)) getOrElse path).normalize()
}

object JvmPath {
  def apply(parts: JPath*): JvmPath = {
    new JvmPath(JPaths.get(parts.head.toString, parts.tail.map(_.toString): _*))
  }
  def apply(parts: String*): Path = new JvmPath(JPaths.get(parts.head, parts.tail: _*), None)
  implicit class RichSwovalPath(val p: Path) extends AnyVal {
    def toJvmPath: JvmPath = p.asInstanceOf[JvmPath]
    def path: JPath = toJvmPath.path.toRealPath()
  }
}
