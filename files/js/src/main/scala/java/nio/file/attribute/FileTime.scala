package java.nio.file.attribute

import java.util.concurrent.TimeUnit

class FileTime(value: Long, timeUnit: TimeUnit) extends Comparable[FileTime] {
  def toMillis(): Long = timeUnit.toMillis(value)
  def compareTo(other: FileTime): Int = toMillis.compareTo(other.toMillis)
}
object FileTime {
  def fromMillis(millis: Long): FileTime = new FileTime(millis, TimeUnit.MILLISECONDS)
}
