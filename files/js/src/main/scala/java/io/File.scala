package java.io

import java.net.{ URI, URL }
import java.nio.file.{ JSPath, Path }

import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.path.{ Path => JPath }
import scala.util.Try

class File(pathname: String) {
  private[this] lazy val absolutePath = JPath.resolve(pathname)
  private[this] lazy val parsed = JPath.parse(absolutePath)
  def canRead: Boolean = Try(Fs.accessSync(pathname, Fs.R_OK)).isSuccess
  def canWrite: Boolean = Try(Fs.accessSync(pathname, Fs.W_OK)).isSuccess
  def exists(): Boolean = Fs.existsSync(absolutePath)
  def getAbsolutePath(): String = absolutePath
  def getAbsoluteFile(): File = new File(getAbsolutePath)
  def getCanonicalPath(): String = JPath.normalize(JPath.resolve(pathname))
  def getCanonicalFile(): File = new File(getCanonicalPath)
  def getName(): String = JPath.basename(pathname)
  def getParent(): String = JPath.dirname(pathname)
  def getParentFile(): File = new File(getParent)
  def getPath(): String = pathname
  def isAbsolute(): Boolean = JPath.isAbsolute(pathname)
  def isDirectory(): Boolean = exists && Fs.statSync(pathname).isDirectory
  def isFile(): Boolean = exists && Fs.statSync(pathname).isFile
  def isHidden(): Boolean = getName.startsWith(".")
  def lastModified(): Long = Fs.statSync(pathname).mtime.getTime.toLong
  def length(): Long = Fs.statSync(pathname).size.toLong
  def createNewFile(): Boolean =
    !exists &&
      Try(Fs.closeSync(Fs.openSync(pathname, "w"))).isSuccess
  def delete(): Boolean =
    Try(if (isDirectory) Fs.rmdirSync(pathname) else Fs.unlinkSync(pathname)).isSuccess
  def deleteOnExit(): Unit = ???
  def list(): Array[String] = listFiles().map(_.toString())
  def list(filter: FilenameFilter): Array[String] = listFiles(filter).map(_.toString)
  private object AllPass extends FileFilter { override def accept(pathname: File): Boolean = true }
  def listFiles(): Array[File] = listFiles(AllPass)
  def listFiles(filter: FilenameFilter): Array[File] =
    listFiles(new FileFilter {
      override def accept(pathname: File): Boolean =
        filter.accept(pathname.getParentFile, pathname.getName)
    })
  def listFiles(filter: FileFilter): Array[File] = {
    if (exists && isDirectory) {
      Fs.readdirSync(absolutePath).toArray.flatMap { f =>
        Some(new File(s"$absolutePath${File.separator}$f")).filter(filter.accept)
      }
    } else {
      null
    }
  }
  def mkdir(): Boolean = !exists && Try(Fs.mkdirSync(pathname)).isSuccess
  def mkdirs(): Boolean = {
    !exists && {
      val parts = new JSPath(JPath.resolve(pathname)).parts
      if (isAbsolute) absolutePath.split(JSPath.regexSep).drop(1)
      else absolutePath.split(JSPath.regexSep)
      parts
        .foldLeft((new File(parsed.root.toOption.getOrElse(JPath.sep)), false)) {
          case ((a, r), p) =>
            val path = new File(s"$a${JPath.sep}$p")
            (path, path.mkdir())
        }
        ._2
    }
  }
  def renameTo(dest: File): Boolean =
    Try(Fs.renameSync(absolutePath, dest.getAbsolutePath)).isSuccess
  def setLastModified(millis: Long): Boolean = {
    if (!exists) createNewFile()
    val atime = Fs.statSync(absolutePath).atime.getTime.toInt
    Try(Fs.utimesSync(absolutePath, atime, (millis / 1000).toInt)).isSuccess
  }
  def setReadOnly(): Boolean = Try(Fs.chmodSync(absolutePath, Integer.decode("0444"))).isSuccess
  def setWritable(writable: Boolean, ownerOnly: Boolean): Boolean = {
    val mode = Fs.statSync(getAbsolutePath).mode.intValue
    val mask = if (ownerOnly) Integer.decode("0200") else Integer.decode("0222")
    Try(Fs.chmodSync(getAbsolutePath, mode & mask)).isSuccess
  }
  def setWritable(writable: Boolean): Boolean = setWritable(writable, ownerOnly = false)
  def setReadable(readable: Boolean, ownerOnly: Boolean): Boolean = {
    val mode = Fs.statSync(getAbsolutePath).mode.intValue
    val mask = if (ownerOnly) Integer.decode("0400") else Integer.decode("0444")
    Try(Fs.chmodSync(getAbsolutePath, mode & mask)).isSuccess
  }
  def setReadable(readable: Boolean): Boolean = setReadable(readable, ownerOnly = false)
  def setExecutable(executable: Boolean, ownerOnly: Boolean): Boolean = {
    val mode = Fs.statSync(getAbsolutePath).mode.intValue
    val mask = if (ownerOnly) Integer.decode("0100") else Integer.decode("0111")
    Try(Fs.chmodSync(getAbsolutePath, mode & mask)).isSuccess
  }
  def setExecutable(executable: Boolean): Boolean = setExecutable(executable, false)
  def canExecute(): Boolean = Try(Fs.accessSync(absolutePath, Fs.X_OK)).isSuccess
  def getTotalSpace(): Long = ???
  def getFreeSpace(): Long = ???
  def getUsableSpace(): Long = ???
  def toPath(): Path = new JSPath(pathname)
  def toURL(): URL = {
    val sanitized =
      if (JPath.sep != "/") absolutePath.replaceAll(JPath.sep, "/") else absolutePath
    new URL("file", "", sanitized)
  }
  def toURI(): URI = toURL.toURI()
  def compareTo(pathname: File): Int = this.pathname.compareTo(pathname.toString)
  override def toString(): String = pathname
}

trait FileFilter {
  def accept(pathname: File): Boolean
}

trait FilenameFilter {
  def accept(parent: File, name: String): Boolean
}

object File {
  val separator: String = JPath.sep
  val separatorChar: Char = JPath.sep.charAt(0)
}
