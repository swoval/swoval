import sbt._
import io.WatchService
import Keys._

import scala.util.Properties
import scala.concurrent.duration._

import com.swoval.watchservice._

object MacOSXWatchServicePlugin extends AutoPlugin {
  override def trigger = allRequirements
  private def createWatchService(interval: Duration, queueSize: Int): WatchService = {
    if (Properties.isMac) new MacOSXWatchService(interval, queueSize)(_ => {})
    else Watched.createWatchService()
  }
  object autoImport {
    lazy val watchLatency = settingKey[Duration]("Set watch latency for continuous builds.")
    lazy val watchQueueSize = settingKey[Int]("Set watch event queue size for each watched file.")
  }
  import autoImport._
  override lazy val projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ Seq(
    pollInterval := 75.milliseconds, // sbt polls the watch service for events at this rate
    watchLatency := 50.milliseconds, // os x file system api buffers events for this duration
    watchQueueSize := 256, // maximum number of buffered events per watched path
    watchService := { () =>
      createWatchService(watchLatency.value, watchQueueSize.value)
    },
  )
}
