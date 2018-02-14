package com.swoval.reflect

import java.net.{ URL, URLClassLoader }

import scala.collection.mutable

case class ChildFirstClassLoader(urls: Seq[URL],
                                 parent: ClassLoader = Thread.currentThread.getContextClassLoader,
                                 loaded: mutable.Map[String, Class[_]] = mutable.Map.empty)
    extends URLClassLoader(urls.toArray, parent)
    with DynamicClassLoader {
  override def loadClass(name: String, resolve: Boolean): Class[_] = {
    val c: Class[_] = try {
      loaded get name match {
        case None        => findClass(name)
        case Some(clazz) => clazz
      }
    } catch {
      case _: ClassNotFoundException => parent.loadClass(name)
    }
    if (resolve) resolveClass(c)
    loaded += name -> c
    c
  }
  override def loadClass(name: String): Class[_] = loadClass(name, resolve = false)
  override def toString = s"ChildFirstLoader($urls, $parent)"
  override def copy(): ChildFirstClassLoader =
    ChildFirstClassLoader(urls, parent, mutable.Map(loaded.toSeq: _*))
}
