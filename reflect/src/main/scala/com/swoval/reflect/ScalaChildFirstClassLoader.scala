package com.swoval.reflect

import java.nio.file.Path
import java.util.{ HashMap => JHashMap, Map => JMap }

case class ScalaChildFirstClassLoader(paths: Seq[Path],
                                      parent: ClassLoader,
                                      loaded: JMap[String, Class[_]])
    extends ChildFirstClassLoader(paths.map(_.toUri.toURL).toArray, parent, loaded)
    with DynamicClassLoader[ScalaChildFirstClassLoader] {
  import scala.collection.JavaConverters._
  println(loaded.asScala.keys.toSeq.sorted mkString "\n")

  override def toString = s"ScalaChildFirstLoader($paths, $parent)"

  override def dup(): ScalaChildFirstClassLoader =
    ScalaChildFirstClassLoader(paths, parent, new JHashMap(loaded))
}

object ScalaChildFirstClassLoader {
  implicit def default: ScalaChildFirstClassLoader =
    Thread.currentThread.getContextClassLoader match {
      case l: ScalaChildFirstClassLoader => l
      case l: ChildFirstClassLoader =>
        ScalaChildFirstClassLoader(Seq.empty, l.dup(), new JHashMap(l.getLoadedClasses))
      case l: DynamicClassLoader[_] =>
        ScalaChildFirstClassLoader(Seq.empty, l.dup(), new JHashMap)
    }

  def apply(parent: ClassLoader): ScalaChildFirstClassLoader = {
    val map: JMap[String, Class[_]] = parent match {
      case l: ChildFirstClassLoader => new JHashMap(l.getLoadedClasses)
      case _                        => new JHashMap
    }
    ScalaChildFirstClassLoader(Seq.empty, parent, map)
  }

  def apply(paths: Seq[Path]): ScalaChildFirstClassLoader =
    ScalaChildFirstClassLoader(paths, Thread.currentThread.getContextClassLoader, new JHashMap)
}
