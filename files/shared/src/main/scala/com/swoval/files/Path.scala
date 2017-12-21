package com.swoval.files

trait Path {
  def createFile(): Path
  def delete(): Boolean
  def exists: Boolean
  def isAbsolute: Boolean
  def isDirectory: Boolean
  def getParent: Path
  def getRoot: Path
  def fullName: String
  def lastModified: Long
  def list(recursive: Boolean, filter: PathFilter = _ => true): Seq[Path]
  def mkdir(): Path
  def mkdirs(): Path
  def name: String
  def normalize: Path
  def parts: Seq[Path]
  def relativize(other: Path): Path
  def renameTo(other: Path): Path
  def resolve(other: Path): Path
  def toAbsolute: Path
  def setLastModifiedTime(millis: Long): Path
  def startsWith(other: Path): Boolean
  def write(content: String): Unit
}

trait PathCompanion {
  def apply(name: String*): Path
  def createTempFile(dir: Path, prefix: String): Path
  def createTempDirectory(dir: Path, prefix: String = "subdir"): Path
}
object Path extends PathCompanion {
  private[this] val companion: PathCompanion = platform.pathCompanion
  def apply(name: String*): Path = companion(name: _*)
  def createTempFile(dir: Path, prefix: String): Path = companion.createTempFile(dir, prefix)
  def createTempDirectory(dir: Path, prefix: String): Path =
    companion.createTempDirectory(dir, prefix)
}
