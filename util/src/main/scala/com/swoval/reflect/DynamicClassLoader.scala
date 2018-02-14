package com.swoval.reflect

trait DynamicClassLoader extends ClassLoader {
  def dup(): DynamicClassLoader
}
object DynamicClassLoader {
  implicit def default: DynamicClassLoader = {
    val loader = Thread.currentThread.getContextClassLoader
    val dc = loader.loadClass("com.swoval.reflect.DynamicClassLoader")
    if (dc.isAssignableFrom(loader.getClass)) {
      dc.cast(dc.getMethod("dup").invoke(loader))
        .asInstanceOf[com.swoval.reflect.DynamicClassLoader]
    } else {
      ChildFirstClassLoader(Seq.empty, loader)
    }
  }
}
