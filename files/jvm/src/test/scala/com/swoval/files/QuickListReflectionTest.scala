package com.swoval.files

object QuickListReflectionTest {
  def main(args: Array[String]): Unit = {
    val quickList = classOf[QuickList]
    val field = quickList.getDeclaredField("INSTANCE")
    field.setAccessible(true)
    val clazz = args.headOption match {
      case Some(c) => Class.forName(c)
      case _       => classOf[NativeDirectoryLister]
    }
    val quickLister = field.get(null)
    val directoryListerField = quickLister.getClass.getDeclaredField("directoryLister")
    directoryListerField.setAccessible(true)
    val directoryLister = directoryListerField.get(quickLister)
    assert(clazz.isAssignableFrom(directoryLister.getClass))
  }
}
