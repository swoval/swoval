package com.swoval.reflect

trait DynamicClassLoader extends ClassLoader {
  def copy(): DynamicClassLoader
}
object DynamicClassLoader {
  implicit def default: DynamicClassLoader = {
    Thread.currentThread.getContextClassLoader match {
      case l: DynamicClassLoader => l.copy()
      case l                     => ChildFirstClassLoader(Seq.empty, l)
    }
  }
}
