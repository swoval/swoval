package com.swoval.files

object QuickListReflectionTest {
  def main(args: Array[String]): Unit = {
    val default = FileTreeViews.getDefault(false)
    val simpleFileTreeView = classOf[SimpleFileTreeView]
    val field = simpleFileTreeView.getDeclaredField("directoryLister")
    field.setAccessible(true)
    val clazz = args.headOption match {
      case Some(c) => Class.forName(c)
      case _ if System.getProperty("os.name", "").toLowerCase.startsWith("win") =>
        classOf[NioDirectoryLister]
      case _ => classOf[NativeDirectoryLister]
    }
    val lister = field.get(default)
    if (!clazz.isAssignableFrom(lister.getClass)) {
      val a =
        args.headOption.getOrElse("com.swoval.files.NativeDirectoryLister")
      val msg = s"Expected $a but got ${lister.getClass.getName}"
      throw new RuntimeException(msg)
    }
  }
}
