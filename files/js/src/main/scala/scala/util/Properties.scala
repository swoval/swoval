package scala.util

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Properties {
  def isMac: Boolean = os.platform == "darwin"
}

@js.native
@JSImport("os", JSImport.Default)
private object os extends js.Object {
  def platform(): String = js.native
}
