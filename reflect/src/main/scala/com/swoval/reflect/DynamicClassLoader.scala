package com.swoval.reflect

case class DynamicClassLoader(classLoader: ChildFirstClassLoader) extends ClassLoader(classLoader) {
  def dup(): DynamicClassLoader = new DynamicClassLoader(classLoader.dup())
}

object DynamicClassLoader {
  implicit def default: DynamicClassLoader = {
    val loader = Thread.currentThread.getContextClassLoader
    loader match {
      case l: DynamicClassLoader    => l.dup()
      case l: ChildFirstClassLoader => new DynamicClassLoader(l)
      case l =>
        new DynamicClassLoader(new ChildFirstClassLoader(Array.empty, l))
    }
  }
}
