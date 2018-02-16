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
  sealed trait Instance extends Any {
    def instance: Object
  }
  case object NullInstance extends Instance {
    def instance: Object = null
  }
  case class InstanceWrapper[+T <: Object](instance: T) extends AnyVal with Instance
  case class Setup(newInstance: () => Instance,
                   method: Method,
                   args: Array[String],
                   loader: ChildFirstClassLoader) {
    class Handle[T](instance: Instance) {
      private[this] val res = new ArrayBlockingQueue[T](1)
      def instance[T](implicit m: Manifest[T]): T = instance match {
        case InstanceWrapper(i: T) => i
        case _ =>
          val msg = s"${instance.instance} is not an instance of ${m.runtimeClass.getName}"
          throw new ClassCastException(msg)
      }
      def shutdown(): Unit = {
        val clazz = method.getDeclaringClass
        Try(method.getDeclaringClass.getDeclaredMethod("shutdown"))
          .recoverWith {
            case _: NoSuchMethodException =>
              Try(clazz.getDeclaredMethod("close"))
          }
          .foreach(_.invoke(instance))
      }
      private[this] val thread = new Thread(s"MainRunner Thread ${method.getName}") {
        override def run() = res.add(method.invoke(instance, args).asInstanceOf[T])
      }
      thread.start()
      def result(): T = res.take()
      def cancel(): Unit = {
        shutdown()
        thread.interrupt()
        thread.join(5.seconds.toMillis)
      }
    }
    def run[T](): Handle[T] = new Handle[T](newInstance())
  }
  object Setup {
    def apply[T <: Object](args: Array[String]): Setup = {
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
                Try(programClass.getDeclaredField("MODULE$").get(null))
                  .map(f => () => InstanceWrapper(f))
                  .getOrElse(() => NullInstance)
              case _ =>
                Try(childFirstLoader.loadClass(s"$m$$").getDeclaredField("MODULE$").get(null))
                  .map(f => () => InstanceWrapper(f))
                  .getOrElse(() => NullInstance)
            }
            Setup(newInstance, mainMethod, programArgs, childFirstLoader)
          } else {
            try {
              val constructor = programClass.getConstructor()
              val newInstance = () =>
                InstanceWrapper(constructor.newInstance().asInstanceOf[Object])
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
  def setup(args: Array[String]): Setup = Setup(args)
  def run[T](args: Array[String]) = setup(args).run[T].result()
  def main(args: Array[String]): Unit = run[Object](args)

  private def stringToURLs(s: String): Seq[URL] = s.split(File.pathSeparator).map(stringToURL)
  private def stringToURL(s: String) = Paths.get(s).toUri.toURL
}
