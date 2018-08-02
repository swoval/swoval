package sbt

import java.io.{ File => JFile }
import java.nio.file.{ WatchEvent, WatchKey, Path => JPath }

import com.swoval.watchservice.Continuously.{ State => CState }
import sbt.internal.BuildStructure
import sbt.internal.io.{ Source, WatchState }
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
    def base: JPath = baseField.get(s).asInstanceOf[JFile].toPath
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
      s.sources.map(s => sbt.internal.io.Source(s.base.toFile, AllPassFilter, NothingFilter))
    )
    Watched.printIfDefined(watched triggeredMessage ws)
  }
}

object Reapply {
  private val method = {
    val clazz = Class.forName("sbt.internal.Load$")
    val instance = clazz.getDeclaredField("MODULE$").get(null)
    try {
      val m = clazz.getMethod("reapply", classOf[Seq[_]], classOf[BuildStructure], classOf[Show[_]])
      (settings: Seq[Setting[_]], structure: BuildStructure, show: Show[_]) =>
        m.invoke(instance, settings, structure, show).asInstanceOf[BuildStructure]
    } catch {
      case _: Exception =>
        val m = clazz.getMethod("reapply",
                                classOf[Seq[_]],
                                classOf[BuildStructure],
                                classOf[Logger],
                                classOf[Show[_]])
        val logger = new Logger {
          override def trace(t: => Throwable): Unit = {}
          override def success(message: => String): Unit = {}
          override def log(level: Level.Value, message: => String): Unit = {}
        }
        (settings: Seq[Setting[_]], structure: BuildStructure, show: Show[_]) =>
          m.invoke(instance, settings, structure, logger, show)
            .asInstanceOf[BuildStructure]
    }
  }
  def apply(newSettings: Seq[Setting[_]],
            structure: BuildStructure,
            showKey: Show[Def.ScopedKey[_]]): BuildStructure =
    method(newSettings, structure, showKey)
}
