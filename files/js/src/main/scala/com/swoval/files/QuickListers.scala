package com.swoval.files

object QuickListers {

  private val nioDirectoryLister: NioDirectoryLister = new NioDirectoryLister()

  def getNio(): QuickLister = new QuickListerImpl(nioDirectoryLister)

  def getNative(): QuickLister = null

  def getDefault(): QuickLister = getNio()

}
