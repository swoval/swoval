package java.nio.file.attribute

import java.nio.file.Path

import com.swoval.files.LinkOption
import io.scalajs.nodejs.fs.Fs

trait BasicFileAttributes {
  def creationTime(): FileTime
  def fileKey(): Object
  def isDirectory(): Boolean
  def isRegularFile(): Boolean
  def isSymbolicLink(): Boolean
  def isOther(): Boolean
  def lastAccessTime(): FileTime
  def lastModifiedTime(): FileTime
  def size(): Long
}

class BasicFileAttributesImpl(p: Path, options: Seq[LinkOption]) extends BasicFileAttributes {
  private[this] val stat = try {
    if (options.contains(LinkOption.NOFOLLOW_LINKS)) Fs.lstatSync(p.toString)
    else Fs.statSync(p.toString)
  } catch { case e: Exception => java.nio.file.Errors.rethrow(p, e) }
  override def creationTime(): FileTime = FileTime.fromMillis(stat.ctime.getTime().toLong)
  override def fileKey(): AnyRef = p
  override def isDirectory: Boolean = stat.isDirectory()
  override def isRegularFile: Boolean = stat.isFile()
  override def isOther: Boolean = !stat.isFile() && !stat.isDirectory() && !stat.isSymbolicLink()
  override def isSymbolicLink: Boolean = stat.isSymbolicLink()
  override def lastAccessTime(): FileTime = FileTime.fromMillis(stat.atime.getTime().toLong)
  override def lastModifiedTime(): FileTime = FileTime.fromMillis(stat.mtime.getTime().toLong)
  override def size(): Long = stat.size.toLong
}
