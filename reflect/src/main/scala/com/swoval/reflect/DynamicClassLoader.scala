package com.swoval.reflect

import java.util.{ HashMap => JHashMap }

trait DynamicClassLoader extends ClassLoader {
  def dup(): DynamicClassLoader
}

object DynamicClassLoader {
  case class Wrapper(c: ChildFirstClassLoader) extends DynamicClassLoader {
    def dup() = Wrapper(c.dup())
  }
  implicit def default: DynamicClassLoader = {
    val loader = Thread.currentThread.getContextClassLoader
    loader match {
      case l: DynamicClassLoader => l.dup()
      case l                     => Wrapper(new ChildFirstClassLoader(Array.empty, l, new JHashMap))
    }
  }
}
