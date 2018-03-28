package sbt

import com.swoval.watchservice.Continuously.{ State => CState }

object WatchedWrapper {
  def printTriggeredMessage(s: CState, w: Watched): Unit =
    Watched.printIfDefined(w triggeredMessage WatchState.empty)
}
