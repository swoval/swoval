package com.swoval.runtime

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * Provides some platform specific properties.
 */
object Platform {
  private[this] val (_isMac, _isWin, _isLinux, _isFreeBSD) = {
    val platform = os.platform()
    (
      platform == "darwin",
      platform == "win32",
      platform == "linux",
      platform.toLowerCase.startsWith("freebsd")
    )
  }
  def isFreeBSD(): Boolean = false
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
