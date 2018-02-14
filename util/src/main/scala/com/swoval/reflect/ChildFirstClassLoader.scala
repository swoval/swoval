package com.swoval.reflect

import java.net.{URL, URLClassLoader}
import java.nio.file.Path

import scala.collection.mutable

case class ChildFirstClassLoader(
    urls: Seq[URL],
    parent: ClassLoader = Thread.currentThread.getContextClassLoader,
    loaded: mutable.Map[String, Class[_]] = mutable.Map.empty)
    extends URLClassLoader(urls.toArray, parent)
    with DynamicClassLoader {
  override def loadClass(name: String, resolve: Boolean): Class[_] = {
    println(s"loading $name $loaded")
    val c: Class[_] = try {
      loaded get name match {
        case None => println(s"no class found for $name")
        case _    => println(s"found class for $name")
      }
      loaded.getOrElse(name, findClass(name))
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
    ChildFirstClassLoader(paths.map(_.toUri.toURL),
                          Thread.currentThread.getContextClassLoader)
}
