package com.swoval.watchservice

import java.nio.file.{ Paths => JPaths }
import java.util.concurrent.{ ArrayBlockingQueue, BlockingQueue, ExecutorService, Executors }

import com.swoval.files.DirectoryWatcher.Callback
import sbt.BasicCommandStrings._
import sbt.BasicCommands.otherCommandParser
import sbt.CommandUtil.withAttribute
import sbt.SourceWrapper.RichSource
import sbt.internal.io.Source
import sbt.{ AttributeKey, Command, State, Watched }

import scala.annotation.tailrec

import sbt.WatchedWrapper._

object Continuously {

  lazy val swovalWatchState = AttributeKey[WatchState]("swovalWatchState")
  sealed trait TriggerEvent
  case object Exit extends TriggerEvent
  case object Triggered extends TriggerEvent
  case class WatchState(sources: Seq[Source],
                        var count: Int = -1,
                        events: BlockingQueue[TriggerEvent] = new ArrayBlockingQueue(1),
                        executor: ExecutorService = Executors.newSingleThreadExecutor()) {
    private def shouldExit(): Boolean = System.in.read match {
      case 10 | 13 => true
      case _       => false
    }
    @tailrec
    private final def signalExit(): Unit = {
      val signalled = try { shouldExit() } catch { case _: InterruptedException => true }
      if (signalled) {
        while (!events.offer(Exit)) {
          events.poll()
        }
        Callbacks.remove(callback)
        executor.shutdownNow()
      } else signalExit()
    }
    private[this] val callback: Callback = { e =>
      if (sources.exists(s => s.accept(JPaths.get(e.path.fullName)))) { events.offer(Triggered) }
    }
    Callbacks.add(callback)
    executor.submit((() => signalExit()): Runnable)
  }
  def executeContinuously(watched: Watched, s: State, next: String, repeat: String): State = {
    val watchState = s.get(swovalWatchState) getOrElse WatchState(watched.watchSources(s))
    watchState.count += 1

    if (watchState.count == 0) {
      (ClearOnFailure :: next :: FailureWall :: repeat :: s).put(swovalWatchState, watchState)
    } else {
      println(s"${watchState.count}. Waiting for source changes... (press enter to interrupt)")
      watchState.events.take match {
        case Exit =>
          s.remove(swovalWatchState)
        case Triggered =>
          watchState.printTriggeredMessage(watched)
          ClearOnFailure :: next :: FailureWall :: repeat :: s
      }
    }
  }
  def continuous: Command =
    Command(ContinuousExecutePrefix, continuousBriefHelp, continuousDetail)(otherCommandParser) {
      (s, arg) =>
        val extracted = sbt.Project.extract(s)
        val default: Boolean =
          extracted.get(MacOSXWatchServicePlugin.autoImport.useDefaultWatchService)
        withAttribute(s, Watched.Configuration, "Continuous execution not configured.") { w =>
          val repeat = ContinuousExecutePrefix + (if (arg.startsWith(" ")) arg else " " + arg)
          if (default) Watched.executeContinuously(w, s, arg, repeat)
          else executeContinuously(w, s, arg, repeat)
        }
    }
}
