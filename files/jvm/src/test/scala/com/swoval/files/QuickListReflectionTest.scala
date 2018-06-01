package com.swoval.files

object QuickListReflectionTest {
  def main(args: Array[String]): Unit = {
    val quickList = classOf[QuickList]
    val field = quickList.getDeclaredField("INSTANCE")
    field.setAccessible(true)
    val clazz = args.headOption match {
      case Some(c) => Class.forName(c)
      case _       => classOf[NativeQuickLister]
    }
    assert(clazz.isAssignableFrom(field.get(null).getClass))
  }
}
