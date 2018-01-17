package sbt

import java.nio.file.{ WatchEvent, WatchKey, Path => JPath }

import sbt.internal.io.{ Source, WatchState }
import sbt.io.WatchService

import scala.concurrent.duration.Duration

/*
 * Workaround class because Source.accept is private to package sbt
 */
object SourceWrapper {
  implicit class RichSource(val s: Source) extends AnyVal {
    def accept(path: java.nio.file.Path) = s.accept(path)
  }
}

object WatchedWrapper {
  implicit class WatchStateWrapper(val s: com.swoval.watchservice.Continuously.WatchState)
      extends AnyVal {
    def printTriggeredMessage(watched: Watched): Unit = {
      val ws = WatchState.empty(
        new WatchService {
          override def init(): Unit = {}
          override def pollEvents(): Map[WatchKey, Seq[WatchEvent[JPath]]] = Map.empty
          override def poll(timeout: Duration): WatchKey = null
          override def close(): Unit = {}
          override def register(path: JPath, events: WatchEvent.Kind[JPath]*): WatchKey = null
        },
        s.sources
      )
      Watched.printIfDefined(watched triggeredMessage ws)
    }
  }
}
