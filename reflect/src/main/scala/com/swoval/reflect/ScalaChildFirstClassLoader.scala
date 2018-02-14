package com.swoval.reflect

import java.net.URL
import java.nio.file.Path
import java.util.{ HashMap => JHashMap, Map => JMap }

case class ScalaChildFirstClassLoader(urls: Seq[URL],
                                      parent: ClassLoader =
                                        Thread.currentThread.getContextClassLoader,
                                      loaded: JMap[String, Class[_]] = new JHashMap)
    extends ChildFirstClassLoader(urls.toArray, parent, loaded)
    with DynamicClassLoader {

  override def toString = s"ScalaChildFirstLoader($urls, $parent)"

  override def dup(): ScalaChildFirstClassLoader =
    ScalaChildFirstClassLoader(urls, parent, new JHashMap(loaded))
}

object ScalaChildFirstClassLoader {
  implicit def default: ScalaChildFirstClassLoader =
    Thread.currentThread.getContextClassLoader match {
      case l: ScalaChildFirstClassLoader => l
      case l: DynamicClassLoader         => ScalaChildFirstClassLoader(Seq.empty, l)
    }

  def apply(parent: ClassLoader): ScalaChildFirstClassLoader =
    ScalaChildFirstClassLoader(Seq.empty, parent)

  def apply(paths: Seq[Path]): ScalaChildFirstClassLoader =
    ScalaChildFirstClassLoader(paths.map(_.toUri.toURL), Thread.currentThread.getContextClassLoader)
}
