package com.swoval.files

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * Provides some platform specific properties.
 */
object Platform {
  private[this] val (_isMac, _isWin, _isLinux) = {
    val platform = os.platform()
    (platform == "darwin", platform == "win32", platform == "linux")
  }
  def isJVM(): Boolean = false
  def isLinux(): Boolean = _isLinux
  def isMac(): Boolean = _isMac
  def isWin(): Boolean = _isWin
  def tmpDir(): String = os.tmpdir()
}
@js.native
@JSImport("os", JSImport.Default)
private object os extends js.Object {
  def platform(): String = js.native
  def tmpdir(): String = js.native
}
