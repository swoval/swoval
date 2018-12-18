package com.swoval.files

object QuickListReflectionTest {
  def main(args: Array[String]): Unit = {
    val default = FileTreeViews.getDefault(false, false)
    val simpleFileTreeView = classOf[SimpleFileTreeView]
    val field = simpleFileTreeView.getDeclaredField("directoryLister")
    field.setAccessible(true)
    val clazz = args.headOption match {
      case Some(c) => Class.forName(c)
      case _       => classOf[NativeDirectoryLister]
    }
    val lister = field.get(default)
    assert(clazz.isAssignableFrom(lister.getClass))
  }
}
