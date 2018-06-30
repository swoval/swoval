package java.nio.file

object FileSystems {
  private[this] val fileSystem = new FileSystem {}
  def getDefault(): FileSystem = fileSystem
}
