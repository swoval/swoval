package com.swoval.reflect

import java.net.{URL, URLClassLoader}
import java.nio.file.Path

case class ChildFirstClassLoader(urls: Seq[URL],
                                 parent: ClassLoader =
                                   Thread.currentThread.getContextClassLoader)
    extends URLClassLoader(urls.toArray, parent)
    with DynamicClassLoader {
  override def loadClass(name: String, resolve: Boolean): Class[_] = {
    val c: Class[_] = try {
      findClass(name)
    } catch {
      case _: ClassNotFoundException =>
        parent.loadClass(name)
    }
    if (resolve) resolveClass(c)
    c
  }
  override def loadClass(name: String): Class[_] =
    loadClass(name, resolve = false)
  override def toString = s"ChildFirstLoader($urls, $parent)"
  override def dup(): ChildFirstClassLoader =
    ChildFirstClassLoader(urls, parent)
}
object ChildFirstClassLoader {
  def apply(paths: Seq[Path]): ChildFirstClassLoader =
    ChildFirstClassLoader(paths.map(_.toUri.toURL),
                          Thread.currentThread.getContextClassLoader)
}
