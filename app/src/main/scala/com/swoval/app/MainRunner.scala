package com.swoval.app

import java.io.File
import java.lang.reflect.{ Method, Modifier }
import java.net.URL
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue

import com.swoval.reflect.ChildFirstClassLoader

import scala.concurrent.duration._
import scala.util.Try

object MainRunner {
  sealed trait Instance[+T >: Null <: Object] {
    def value: T
    def shutdownable: Shutdownable = value match {
      case s: Shutdownable => s
      case _ =>
        () =>
          {}
    }
  }
  case object NullInstance extends Instance[Null] {
    def value: Null = null
  }
  case class InstanceWrapper[+T >: Null <: Object](value: T) extends Instance[T]
  case class Setup[T >: Null <: Object](newInstance: () => Instance[T],
                                        method: Method,
                                        args: Array[String],
                                        loader: ChildFirstClassLoader) {
    class Handle[R](instance: Instance[T]) {
      def getInstance: T = instance.value
      private[this] val res = new ArrayBlockingQueue[T](1)
      def shutdown(): Unit = {
        val shutdownable = instance.shutdownable
        shutdownable.shutdown()
        shutdownable.waitForShutdown()
      }
      private[this] val thread = new Thread(s"MainRunner Thread ${method.getName}") {
        override def run(): Unit = res.add(method.invoke(instance.value, args).asInstanceOf[T])
      }
      thread.start()
      def result(): T = res.take()
      def cancel(): Unit = {
        shutdown()
        thread.interrupt()
        thread.join(5.seconds.toMillis)
      }
    }
    def run[R](): Handle[R] = new Handle[R](newInstance())
  }
  object Setup {
    def apply[T >: Null <: Object](args: Array[String]): Setup[T] = {
      def argFor(name: String): Option[String] =
        args.iterator.dropWhile(_ != name).drop(1).toSeq.headOption

      val urls = (argFor("--swoval-reload-classpath").toSeq
        .flatMap(stringToURLs) ++ Option(System.getProperty("swoval.reload.class.path")).toSeq
        .flatMap(stringToURLs)).distinct
      val childFirstLoader = new ChildFirstClassLoader(urls.toArray)
      argFor("--main") match {
        case Some(m) =>
          val programArgs = args.drop(args.lastIndexOf(m) + 1)
          val programClass = childFirstLoader.loadClass(m)
          val mainMethod =
            Try(programClass.getDeclaredMethod("main", classOf[Array[String]])).recoverWith {
              case _: NoSuchMethodException =>
                Try(programClass.getDeclaredMethods
                  .filter(m => m.getParameterTypes.sameElements(Seq(classOf[Array[String]]))) match {
                  case Array(m) => m
                  case m =>
                    val msg = s"Couldn't find unambiguous main method in ${m.toSeq}"
                    throw new IllegalArgumentException(msg)
                })
            }.get
          if (Modifier.isStatic(mainMethod.getModifiers)) {
            val newInstance = m match {
              case c if c.endsWith("$") =>
                Try(programClass.getDeclaredField("MODULE$").get(null).asInstanceOf[T])
                  .map(f => () => InstanceWrapper(f))
                  .getOrElse(() => NullInstance)
              case _ =>
                Try(
                  childFirstLoader
                    .loadClass(s"$m$$")
                    .getDeclaredField("MODULE$")
                    .get(null)
                    .asInstanceOf[T])
                  .map(f => () => InstanceWrapper[T](f))
                  .getOrElse(() => NullInstance)
            }
            Setup(newInstance, mainMethod, programArgs, childFirstLoader)
          } else {
            try {
              val constructor = programClass.getConstructor()
              val newInstance = () => InstanceWrapper(constructor.newInstance().asInstanceOf[T])
              Setup(newInstance, mainMethod, programArgs, childFirstLoader)
            } catch {
              case _: NoSuchMethodException =>
                val msg = s"Usage: $m neither has a static main method nor" +
                  "a no argument constructor"
                throw new IllegalArgumentException(msg)
            }
          }
        case _ =>
          val msg = "Usage: must specify a fully qualified main class with '--main'"
          throw new IllegalArgumentException(msg)
      }
    }
  }
  def setup[T >: Null <: Object](args: Array[String]): Setup[T] = Setup[T](args)
  def run[T](args: Array[String]) = setup[Object](args).run[T]().result()
  def main(args: Array[String]): Unit = run[Object](args)

  private def stringToURLs(s: String): Seq[URL] = s.split(File.pathSeparator).map(stringToURL)
  private def stringToURL(s: String) = Paths.get(s).toUri.toURL
}
