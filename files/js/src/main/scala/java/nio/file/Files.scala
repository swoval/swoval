package java.nio.file

import java.io.{ File, IOException }
import java.nio.file.attribute.{ FileAttribute, FileTime }
import java.util.concurrent.TimeUnit

import io.scalajs.nodejs.fs.Fs

import scala.util.Try

object Files {
  def createDirectory(path: Path, attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    if (!path.toFile().mkdir()) throw new IOException(s"Couldn't create path $path")
    path
  }
  def createDirectories(path: Path, attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    path.toFile().mkdirs()
    path
  }
  def createFile(path: Path, attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    if (!path.toFile().createNewFile()) throw new IOException(s"Couldn't create file $path")
    path
  }
  def createTempDirectory(path: Path,
                          prefix: String,
                          attrs: Array[FileAttribute[_]] = Array.empty): Path =
    new JSPath(Fs.realpathSync(Fs.mkdtempSync(path.resolve(prefix).toString())))
  def createTempFile(dir: Path,
                     prefix: String,
                     suffix: String,
                     attrs: Array[FileAttribute[_]] = Array.empty): Path = {
    val random = new scala.util.Random().alphanumeric.take(10).mkString
    val path = s"$dir${File.separator}$prefix${random}${Option(suffix).getOrElse("")}"
    Fs.closeSync(Fs.openSync(path, "w"))
    new JSPath(path)
  }
  def deleteIfExists(path: Path): Boolean = path.toFile.exists && path.toFile.delete
  def exists(path: Path, options: Array[LinkOption] = Array.empty): Boolean = path.toFile.exists
  def isDirectory(path: Path, linkOptions: Array[LinkOption] = Array.empty): Boolean = {
    Try(path.toFile.isDirectory).getOrElse(false)
  }
  def move(src: Path, target: Path, options: Array[CopyOption] = Array.empty): Path = {
    if (!src.toFile.renameTo(target.toFile()))
      throw new IOException(s"Couldn't move $src to $target")
    target
  }
  def getLastModifiedTime(path: Path, linkOptions: Array[LinkOption] = Array.empty): FileTime =
    FileTime.fromMillis(path.toFile.lastModified)
  def readAllBytes(path: Path): Array[Byte] = {
    val buf = Fs.readFileSync(path.toRealPath().toString)
    buf.values.map(b => (b & 0xFF).toByte).toArray
  }
  def setLastModifiedTime(path: Path, fileTime: FileTime): Path = {
    path.toFile.setLastModified(fileTime.toMillis)
    path
  }
  def write(path: Path, bytes: Array[Byte], options: Array[OpenOption] = Array.empty): Path = {
    Fs.writeFileSync(path.toRealPath().toString, new String(bytes))
    path
  }
}
