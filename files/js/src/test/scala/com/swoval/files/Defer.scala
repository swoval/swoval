package com.swoval.files

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers._

object Defer {
  def apply[T](duration: FiniteDuration)(thunk: => T): Unit = setTimeout(duration)(thunk)
}
