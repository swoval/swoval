package com.swoval.watchservice

import java.lang.System.currentTimeMillis
import java.util.concurrent.{ ArrayBlockingQueue, BlockingQueue, ExecutorService, Executors }

import com.swoval.concurrent.ThreadFactory
import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.{ FileCache, Path, PathFilter }
import com.swoval.watchservice.CloseWatchPlugin.autoImport._
import sbt.BasicCommandStrings._
import sbt.BasicCommands._
import sbt.CommandUtil._
import sbt.WatchedWrapper._
import sbt._

import scala.annotation.tailrec
import scala.concurrent.duration._

object Continuously {
  def continuous: Command =
    Command(ContinuousExecutePrefix, continuousBriefHelp, continuousDetail)(otherCommandParser) {
      (s, arg) =>
        val extracted = sbt.Project.extract(s)
        withAttribute(s, Watched.Configuration, "Continuous execution not configured.") { w =>
          val repeat = ContinuousExecutePrefix + (if (arg.startsWith(" ")) arg else " " + arg)
          if (extracted.get(closeWatchUseDefaultWatchService))
            Watched.executeContinuously(w, s, arg, repeat)
          else
            startWatch(w, s, extracted, arg)
        }
    }
  lazy val closeWatchState = AttributeKey[State]("closeWatchState")
  case class State(
      command: String,
      sources: Seq[SourcePath],
      cache: FileCache,
      logger: Logger,
      antiEntropy: Duration,
      onTrigger: State => Unit
  ) {
    def waitForEvents(state: sbt.State): sbt.State = {
      lazy val nextState =
        (ClearOnFailure :: command :: FailureWall :: State.repeat(command) :: state)
          .put(closeWatchState, this)
      count += 1
      if (count == 0) {
        nextState
      } else {
        println(s"$count. Waiting for source changes... (press enter to interrupt)")
        events.take match {
          case Exit =>
            // The executor thread has already been cleaned up in signalExit
            state.remove(closeWatchState)
          case e =>
            debug(s"Build triggered by $e")
            onTrigger(this)
            nextState
        }
      }
    }

    private[this] val callback: Callback = { e =>
      if (sources.exists(s => s.filter(e.path))) {
        offer(Triggered(e.path))
      } else {
        debug(s"No source filter found for $e")
      }
    }
    private[this] val callbackHandle = cache.addCallback(callback)
    private[this] val tag = "[com.swoval.watchservice]"
    private[this] val events: BlockingQueue[TriggerEvent] = new ArrayBlockingQueue(1)
    private[this] val executor: ExecutorService = Executors.newSingleThreadExecutor(
      new ThreadFactory("com.swoval.watchservice.Continuously.InputThread"))
    private[this] var lastTriggerEvent: TriggerEvent = Exit
    private[this] var count = -1

    private[this] def debug(msg: => String): Unit = logger.debug(s"$tag $msg")
    private[this] def init(): Unit = {
      def sanitized(p: SourcePath) = p.base match {
        case dir if dir.isDirectory => (dir -> p.recursive)
        case f                      => (f.getParent -> p.recursive)
      }
      val filter = new PathFilter {
        override def apply(p: Path) = sources.exists(_.filter(p))
      }
      sources.map(sanitized).distinct.foreach((cache.register _).tupled)
      executor.submit(new Runnable { override def run() { signalExit() } })
    }
    private[this] def offer(e: TriggerEvent) = callback.synchronized {
      if (!TriggerEvent.tooSoon(lastTriggerEvent, e, antiEntropy)) {
        debug(s"Not offering $e due to anti-entropy constraint")
        false
      } else if (events.offer(e)) {
        lastTriggerEvent = e
        debug(s"Successfully offered $e")
        true
      } else {
        logger.info(s"Not offering event $e due to pending event ${events.peek}")
        false
      }
    }
    @tailrec
    private[this] final def signalExit(): Unit = {
      val signalled = try { shouldExit() } catch { case _: InterruptedException => true }
      if (signalled) {
        while (!offer(Exit)) {
          events.poll()
        }
        cache.removeCallback(callbackHandle)
        executor.shutdownNow()
      } else signalExit()
    }
    private[this] def shouldExit(): Boolean = System.in.read match {
      case 10 | 13 => true
      case _       => false
    }
    init()
  }
  private[this] sealed trait TriggerEvent
  private[this] object TriggerEvent {
    def tooSoon(l: TriggerEvent, r: TriggerEvent, antiEntropy: Duration): Boolean =
      (l, r) match {
        case (Triggered(lp, lat), Triggered(rp, rat)) =>
          lp != rp || Math.abs(lat - rat).milliseconds > antiEntropy
        case _ => true
      }
  }
  private[this] case object Exit extends TriggerEvent
  private[this] case class Triggered(path: Path, triggeredAtMs: Long = currentTimeMillis)
      extends TriggerEvent
  private[this] object State {
    def repeat(command: String) = s"$ContinuousExecutePrefix ${command.trim}"
  }

  private[this] def startWatch(w: Watched,
                               s: sbt.State,
                               extracted: Extracted,
                               arg: String): sbt.State = s.get(closeWatchState) match {
    case Some(ws) => ws.waitForEvents(s)
    case None =>
      val tasks = arg.split(";") match {
        case Array(_, rest @ _*) if rest.nonEmpty => rest.map(_.trim)
        case Array(a)                             => Seq(a.trim)
      }
      val sources = tasks.flatMap(t =>
        extracted.runInputTask(closeWatchTransitiveSources, s" $t", s)._2).distinct
      val log = Project.structure(s).streams(s)(Keys.streams in Global).log
      val antiEntropy = extracted.get(closeWatchAntiEntropy)
      val cache = extracted.get(closeWatchFileCache)
      val onTrigger: State => Unit = printTriggeredMessage(_, w)
      log.debug(s"Found watch sources:\n${sources.sortBy(_.base).mkString("\n")}")
      State(arg, sources, cache, log, antiEntropy, onTrigger).waitForEvents(s)
  }
}
