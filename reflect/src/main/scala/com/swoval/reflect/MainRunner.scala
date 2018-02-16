package com.swoval.reflect

import java.io.File
import java.net.URL
import java.nio.file.Paths

object MainRunner {
  def main(args: Array[String]) {
    def argFor(name: String): Option[String] =
      args.iterator.dropWhile(_ != name).drop(1).toSeq.headOption

    val urls = (argFor("--swoval-reload-classpath").toSeq
      .flatMap(stringToURLs) ++ Option(System.getProperty("swoval.reload.class.path")).toSeq
      .flatMap(stringToURLs)).distinct
    val childFirstLoader = new ChildFirstClassLoader(urls.toArray)
    Thread.currentThread.setContextClassLoader(childFirstLoader)
    argFor("--main") match {
      case Some(m) =>
        val programArgs = args.drop(args.lastIndexOf(m))
        val serverClass = childFirstLoader.loadClass("com.swoval.Server$")
        val server = serverClass.getDeclaredField("MODULE$").get(null)
        val main = serverClass.getDeclaredMethod("main", classOf[Array[String]])
        main.invoke(server, programArgs)
      case _ =>
        println("Usage: must specify a fully qualified main class with '--main'")
    }
  }

  private def stringToURLs(s: String): Seq[URL] = s.split(File.pathSeparator).map(stringToURL)
  private def stringToURL(s: String) = Paths.get(s).toUri.toURL
}
