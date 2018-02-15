package com.swoval.reflect

import java.io.File
import java.nio.file.{ Path, Paths }

object MainRunner {
  def main(args: Array[String]) {
    def argFor(name: String): Option[String] =
      args.iterator.dropWhile(_ != name).drop(1).toSeq.headOption

    val urls = (argFor("--swoval-reload-classpath").toSeq
      .flatMap(stringToPaths) ++ Option(System.getProperty("swoval.reload.class.path")).toSeq
      .flatMap(stringToPaths)).distinct
    val childFirstLoader = ScalaChildFirstClassLoader(urls)
    Thread.currentThread.setContextClassLoader(childFirstLoader)
    val serverClass = childFirstLoader.loadClass("com.swoval.Server$")
    val server = serverClass.getDeclaredField("MODULE$").get(null)
    val main = serverClass.getDeclaredMethod("main", classOf[Array[String]])
    main.invoke(server, args)
  }

  private def stringToPaths(s: String): Seq[Path] =
    s.split(File.pathSeparator).map(stringToURL)

  def stringToURL(s: String) = Paths.get(s)
}
