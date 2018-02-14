package com.swoval

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.{ Files, Paths }
import java.util.concurrent.ArrayBlockingQueue

import com.swoval.files._
import com.swoval.files.apple.Flags

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{ Failure, Properties, Try }

object Bootstrap {
  def main(args: Array[String]): Unit = {
    val paths = java.lang.System
      .getProperty("play.full.class.path")
      .split(File.pathSeparator)
      .map(Paths.get(_))
    println(java.lang.System.getProperty("java.class.path").split(":").mkString("\n"))
    val urls = paths.map(_.toUri.toURL)
    println(s"URLS: $urls")
    class AppLoader extends URLClassLoader(urls) {
      override def loadClass(name: String, resolve: Boolean): Class[_] = {
        val c: Class[_] = try {
          findClass(name)
        } catch {
          case _: ClassNotFoundException => getParent.loadClass(name)
        }
        if (resolve) resolveClass(c)
        c
      }
      override def loadClass(name: String): Class[_] = loadClass(name, resolve = false)
    }
    val refresh = new ArrayBlockingQueue[Path](1)
    val callback: DirectoryWatcher.Callback = e => {
      println(e.path)
      refresh.offer(e.path)
    }
    val watcher: DirectoryWatcher =
      if (Properties.isMac)
        AppleDirectoryWatcher(5.milliseconds, new Flags.Create().setFileEvents().setNoDefer())(
          callback)
      else
        new NioDirectoryWatcher(callback)
    paths.foreach(p => watcher.register(JvmPath(p)))
    val NoSuchMethod: Try[Method] = Failure(new NoSuchMethodException)
    @tailrec def loop(): Unit = {
      val shutdown: Runnable = args match {
        case Array(qualifiedClass, rest @ _*) =>
          var shutdownM: Try[Method] = NoSuchMethod
          var instance: Object = null
          val thread = new Thread(() => {
            val loader = new AppLoader
            val cls = loader.loadClass(s"$qualifiedClass$$")
            instance = cls.getDeclaredField("MODULE$").get(null)
            val main = cls.getDeclaredMethod("main", classOf[Array[String]])
            shutdownM = Try(cls.getDeclaredMethod("shutdown"))
            println("starting main server")
            main.invoke(instance, rest.toArray)
          })
          thread.start()
          () =>
            {
              println("shutting down")
              shutdownM.foreach(_.invoke(instance))
              thread.interrupt()
              thread.join()
            }
      }
      val path = refresh.take()
      println(s"got a refresh event $path")
      shutdown.run()
      refresh.clear()
      println(refresh)
      loop()
    }
    loop()
  }
}
