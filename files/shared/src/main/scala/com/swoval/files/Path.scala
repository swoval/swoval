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
  def list(recursive: Boolean, filter: PathFilter = (_: Path) => true): Seq[Path]
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
  implicit object ordering extends Ordering[Path] {
    override def compare(l: Path, r: Path): Int = l.fullName.compare(r.fullName)
  }
  def apply(name: String*): Path = companion(name: _*)
  def createTempFile(dir: Path, prefix: String): Path = companion.createTempFile(dir, prefix)
  def createTempDirectory(dir: Path, prefix: String): Path =
    companion.createTempDirectory(dir, prefix)
  trait DelegatePath extends Path {
    def path: Path
    override def hashCode: Int = path.hashCode
    override def equals(other: Any): Boolean = path.equals(other)
    override def createFile(): Path = path.createFile()
    override def delete(): Boolean = path.delete()
    override def exists: Boolean = path.exists
    override def isAbsolute: Boolean = path.isAbsolute
    override def isDirectory: Boolean = path.isDirectory
    override def getParent: Path = path.getParent
    override def getRoot: Path = path.getRoot
    override def fullName: String = path.fullName
    override def lastModified: Long = path.lastModified
    override def list(recursive: Boolean, filter: PathFilter): Seq[Path] =
      path.list(recursive, filter)
    override def mkdir(): Path = path.mkdir()
    override def mkdirs(): Path = path.mkdirs()
    override def name: String = path.name
    override def normalize: Path = path.normalize
    override def parts: Seq[Path] = path.parts
    override def relativize(other: Path): Path = path.relativize(other)
    override def renameTo(other: Path): Path = path.renameTo(other)
    override def resolve(other: Path): Path = path.resolve(other)
    override def toAbsolute: Path = path.toAbsolute
    override def setLastModifiedTime(millis: Long): Path = path.setLastModifiedTime(millis)
    override def startsWith(other: Path): Boolean = path.startsWith(other)
    override def write(content: String): Unit = path.write(content)
    override def toString: String = s"DelegatePath($path)"
  }
}
