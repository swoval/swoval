package sbt

import java.io.{ File => JFile }
import java.nio.file.{ Paths, WatchEvent, WatchKey, Path => JPath }

import com.swoval.files.{ JvmPath, Path => SPath }
import com.swoval.watchservice.Continuously.{ State => CState }
import sbt.internal.io.{ Source, WatchState }
import sbt.internal.{ BuildStructure, Load }
import sbt.io.WatchService

import scala.concurrent.duration.Duration

/*
 * Workaround class because Source.accept is private to package sbt
 */
object SourceWrapper {
  private[this] val baseField = classOf[Source].getDeclaredField("base")
  private[this] val excludeField = classOf[Source].getDeclaredField("excludeFilter")
  private[this] val includeField = classOf[Source].getDeclaredField("includeFilter")
  Seq(baseField, excludeField, includeField) foreach (f => f.setAccessible(true))
  implicit class RichSource(val s: Source) extends AnyVal {
    def accept(path: JPath) = s.accept(path)
    def base: SPath = JvmPath(baseField.get(s).asInstanceOf[JFile].toPath)
    def exclude: FileFilter = excludeField.get(s).asInstanceOf[FileFilter]
    def include: FileFilter = includeField.get(s).asInstanceOf[FileFilter]
  }
}

object WatchedWrapper {
  def printTriggeredMessage(s: CState, watched: Watched): Unit = {
    val ws = WatchState.empty(
      new WatchService {
        override def init(): Unit = {}
        override def pollEvents(): Map[WatchKey, Seq[WatchEvent[JPath]]] = Map.empty
        override def poll(timeout: Duration): WatchKey = null
        override def close(): Unit = {}
        override def register(path: JPath, events: WatchEvent.Kind[JPath]*): WatchKey = null
      },
      s.sources.map(s =>
        sbt.internal.io.Source(Paths.get(s.base.fullName).toFile, AllPassFilter, NothingFilter))
    )
    Watched.printIfDefined(watched triggeredMessage ws)
  }
}

object Reapply {
  def apply(newSettings: Seq[Setting[_]],
            structure: BuildStructure,
            showKey: Show[Def.ScopedKey[_]]): BuildStructure =
    Load.reapply(newSettings, structure)(showKey)
}
