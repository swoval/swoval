package com.swoval.watchservice

import scala.util.control.NoStackTrace

case class NoSuchTaskException(key: String) extends NoStackTrace {
  override def getMessage: String = s"Couldn't find task with key: '$key'"
}
