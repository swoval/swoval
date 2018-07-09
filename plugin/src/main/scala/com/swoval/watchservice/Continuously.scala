package com.swoval.watchservice

import java.lang.System.currentTimeMillis
import java.nio.file.{ Files, Path }
import java.util.concurrent.{ ArrayBlockingQueue, BlockingQueue, ExecutorService, Executors }

import com.swoval.concurrent.ThreadFactory
import com.swoval.files.Directory.{ Entry, Observer, OnChange }
import com.swoval.files._
import com.swoval.watchservice.CloseWatchPlugin.autoImport._
import sbt.BasicCommandStrings._
import sbt.BasicCommands._
import sbt.CommandUtil._
import sbt.WatchedWrapper._
import sbt._

import scala.annotation.tailrec
import scala.concurrent.duration._
import CloseWatchPlugin.PathWatcherOps

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
      cache: FileCache[Path],
      logger: Logger,
      antiEntropy: Duration,
      onTrigger: State => Unit
  ) {
    @tailrec
    final def waitForEvents(state: sbt.State): sbt.State = {
      lazy val nextState =
        (ClearOnFailure :: command :: FailureWall :: State.repeat(command) :: state)
          .put(closeWatchState, this)
      count += 1
      if (count == 0) {
        nextState
      } else {
        println(s"$count. Waiting for source changes... (press enter to interrupt)")
        events.take match {
          case Init => waitForEvents(state)
          case Exit =>
            // The executor thread has already been cleaned up in signalExit
            debug("Received exit")
            state.remove(closeWatchState)
          case e =>
            debug(s"Build triggered by $e")
            onTrigger(this)
            nextState
        }
      }
    }

    private[this] val onChange: OnChange[Path] = new OnChange[Path] {
      override def apply(cacheEntry: Entry[Path]): Unit = {
        if (sources.exists(s => s.filter.accept(cacheEntry))) {
          offer(Triggered(cacheEntry.getPath))
        } else {
          debug(s"No source filter found for ${cacheEntry.getPath}")
        }
      }
    }
    private[this] val callbackHandle = cache.addCallback(onChange)
    private[this] val tag = "[com.swoval.watchservice]"
    private[this] val events: BlockingQueue[TriggerEvent] = new ArrayBlockingQueue(1)
    private[this] val executor: ExecutorService = Executors.newSingleThreadExecutor(
      new ThreadFactory("com.swoval.watchservice.Continuously.InputThread"))
    private[this] var lastTriggerEvent: TriggerEvent = Init
    private[this] var count = -1

    private[this] def debug(msg: => String): Unit = logger.debug(s"$tag $msg")
    private[this] def init(): Unit = {
      while (System.in.available > 0) System.in.read(new Array[Byte](System.in.available()))
      def sanitized(p: SourcePath) = p.base match {
        case dir if Files.isDirectory(dir) => dir -> p.recursive
        case f                             => f.getParent -> p.recursive
      }
      val filter = new Directory.EntryFilter[Path] {
        override def accept(cacheEntry: Entry[_ <: Path]): Boolean =
          sources.exists(_.filter.accept(cacheEntry))
      }
      sources.map(sanitized).distinct.foreach { case (s, r) => cache.register(s, r) }
      executor.submit(new Runnable { override def run() { signalExit() } })
    }
    private[this] def offer(e: TriggerEvent): Boolean = onChange.synchronized {
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
        cache.removeObserver(callbackHandle)
        while (!offer(Exit)) {
          events.clear()
        }
        executor.shutdownNow()
      } else signalExit()
    }
    @tailrec
    private[this] def shouldExit(): Boolean = {
      System.in.available match {
        case i if i > 0 =>
          System.in.read match {
            case 10 | 13 => true
            case _       => false
          }
        case _ =>
          Thread.sleep(5)
          shouldExit()
      }
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
  private[this] case object Init extends TriggerEvent
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
      val sources = tasks
        .flatMap(t => extracted.runInputTask(closeWatchTransitiveSources, s" $t", s)._2)
        .distinct
      val log = Project.structure(s).streams(s)(Keys.streams in Global).log
      val antiEntropy = extracted.get(closeWatchAntiEntropy)
      val cache = CloseWatchPlugin._internalFileCache
      val onTrigger: State => Unit = printTriggeredMessage(_, w)
      log.debug(s"Found watch sources:\n${sources.sortBy(_.base).mkString("\n")}")
      State(arg, sources, cache, log, antiEntropy, onTrigger).waitForEvents(s)
  }
}
