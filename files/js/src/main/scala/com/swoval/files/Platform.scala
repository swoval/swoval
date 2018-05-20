package com.swoval.files

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Platform {
  def isMac(): Boolean = os.platform() == "darwin"
  def tmpDir(): String = os.tmpdir()
}
@js.native
@JSImport("os", JSImport.Default)
private object os extends js.Object {
  def platform(): String = js.native
  def tmpdir(): String = js.native
}
