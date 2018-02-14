package com.swoval.reflect

import java.net.{ URL, URLClassLoader }
import java.nio.file.Path

import scala.collection.mutable

case class ChildFirstClassLoader(urls: Seq[URL],
                                 parent: ClassLoader = Thread.currentThread.getContextClassLoader,
                                 loaded: mutable.Map[String, Class[_]] = mutable.Map.empty)
    extends URLClassLoader(urls.toArray, parent)
    with DynamicClassLoader
    with HotSwapClassLoader {
  def this(loader: ClassLoader) =
    this(Seq.empty, loader, mutable.Map.empty)
  if (loaded.isEmpty) loadClassses()
  override def addToCache(name: String, clazz: Class[_]): Unit =
    loaded += name -> clazz
  override def loadClass(name: String, resolve: Boolean): Class[_] = {
    val c: Class[_] = try {
      loaded.getOrElse(name, {
        if (name.startsWith("java.") || name.startsWith("sun.")) parent.loadClass(name)
        else findClass(name)
      })
    } catch {
      case _: ClassNotFoundException =>
        parent.loadClass(name)
    }
    if (resolve) resolveClass(c)
    loaded += name -> c
    c
  }
  override def loadClass(name: String): Class[_] =
    loadClass(name, resolve = false)
  override def toString = s"ChildFirstLoader($urls, $parent)"
  override def dup(): ChildFirstClassLoader =
    ChildFirstClassLoader(urls, parent, mutable.Map(loaded.toSeq: _*))
}
object ChildFirstClassLoader {
  implicit def default: ChildFirstClassLoader =
    Thread.currentThread.getContextClassLoader match {
      case l: ChildFirstClassLoader => l
      case l: DynamicClassLoader    => ChildFirstClassLoader(Seq.empty, l)
    }
  def apply(paths: Seq[Path]): ChildFirstClassLoader =
    ChildFirstClassLoader(paths.map(_.toUri.toURL), Thread.currentThread.getContextClassLoader)
}
