package com.swoval.reflect

import java.util.{ HashMap => JHashMap }

trait DynamicClassLoader extends ClassLoader with CloneableClassLoader {
  override def dup: DynamicClassLoader
}

object DynamicClassLoader {
  implicit def default: DynamicClassLoader = {
    val loader = Thread.currentThread.getContextClassLoader
    loader match {
      case l: DynamicClassLoader => l.dup
      case l                     => ScalaChildFirstClassLoader(Seq.empty, l, new JHashMap)
    }
  }
}
