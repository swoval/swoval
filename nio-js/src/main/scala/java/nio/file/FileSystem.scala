package java.nio.file

import java.io.Closeable

import com.swoval.runtime.Platform
import scala.collection.JavaConverters._

trait FileSystem extends Closeable {
  def getRootDirectories(): java.lang.Iterable[Path] =
    if (Platform.isWin) {
      List(Paths.get("C:\\")).asJava
    } else {
      List(Paths.get("/")).asJava
    }
  def close(): Unit = {}
}
