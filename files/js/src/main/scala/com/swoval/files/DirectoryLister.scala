package com.swoval.files

import com.swoval.files.QuickListerImpl.ListResults

private[files] trait DirectoryLister {
  def apply(dir: String, followLinks: Boolean): ListResults
}
