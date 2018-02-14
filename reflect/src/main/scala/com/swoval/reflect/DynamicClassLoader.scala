package com.swoval.reflect

trait DynamicClassLoader extends ClassLoader {
  def dup(): DynamicClassLoader
}

object DynamicClassLoader {
  implicit def default: DynamicClassLoader = {
    val loader = Thread.currentThread.getContextClassLoader
    loader match {
      case l: DynamicClassLoader => l.dup()
      case l                     => ScalaChildFirstClassLoader(Seq.empty, l)
    }
  }
}
