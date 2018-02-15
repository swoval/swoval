package com.swoval.reflect

import java.util.{ HashMap => JHashMap }

trait DynamicClassLoader[T <: ClassLoader with DynamicClassLoader[T]]
    extends ClassLoader
    with CloneableClassLoader {
  self: ClassLoader =>
  override def dup: T
}

object DynamicClassLoader {
  implicit def default[_]: DynamicClassLoader[_] = {
    val loader = Thread.currentThread.getContextClassLoader
    loader match {
      case l: DynamicClassLoader[_] => l.dup
      case l                        => ScalaChildFirstClassLoader(Seq.empty, l, new JHashMap)
    }
  }
}
