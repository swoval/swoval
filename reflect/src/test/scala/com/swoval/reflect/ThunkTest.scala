package com.swoval.reflect

import java.nio.file.{ Files, Path, Paths }

import utest._

import scala.collection.JavaConverters._

class Buzz

object Foo {
  def buzz(x: Buzz): Int = 3

  def bar(x: Int): Int = x + 1

  def add(x: Int, y: Long): Long = x + y
}

object Bar {
  def add(x: Int, y: Long): Long = x + y

  def buzz(x: Buzz): Int = 3
}

object ThunkTest extends TestSuite {
  val initLoader = Thread.currentThread.getContextClassLoader
  val tests = Tests {
    'run - {
      //      'thunk - {
      //        'reflectively - {
      //          'classArguments - {
      //            Thunk(Foo.buzz(new Buzz)) ==> 3
      //          }
      //          'primitiveArguments - {
      //            Thunk(Foo.bar(1)) ==> 2
      //          }
      //          'variableArguments - {
      //            val x = 3
      //            val y = 4L
      //            Thunk(Foo.add(x, y)) ==> x + y
      //          }
      //        }
      //      }
      'strict - {
        'strict - {
          //Thunk(Foo.add(1, 2), true) ==> 3
          //println(Thread.currentThread.getContextClassLoader)
          Thunk(Foo.buzz(new Buzz), true) ==> 3
        }
        //        'reflective - {
        //          Thunk(Foo.buzz(new Buzz), false) ==> 3
        //          Thunk(Foo.add(1, 2), false) ==> 3
        //        }
      }
    }

    def withLoader[R](f: (Path, ScalaChildFirstClassLoader) => R) = {
      val dir = Files.createTempDirectory("reflective-thunk-test")
      val thread = Thread.currentThread
      val initLoader =
        thread.getContextClassLoader.asInstanceOf[ScalaChildFirstClassLoader]
      println("\n")
      //println(initLoader.loaded.asScala.keySet.filter(_ startsWith "com.swoval") mkString "\n")
      println("\n")
      try {
        val loader = initLoader.dup().copy(urls = Seq(dir.toUri.toURL))
        thread.setContextClassLoader(loader)
        f(dir, loader)
      } finally {
        thread.setContextClassLoader(initLoader)
        Files
          .walk(dir)
          .iterator
          .asScala
          .toIndexedSeq
          .sortBy(_.toString)
          .reverse
          .foreach(Files.deleteIfExists(_))
      }
    }

    'reload - {
      val resourcePath = Paths.get("src/test/resources").toAbsolutePath

      def test(digit: Long, includeBuzz: Boolean = false, needIntercept: Boolean = true): Unit =
        withLoader { (path, l) =>
          val dir = Files.createDirectories(path.resolve("com/swoval/reflect"))
          Files.copy(resourcePath.resolve(s"Bar$$.class.$digit"), dir.resolve("Bar$.class"))
          val checkBuzz = () => Thunk(Bar.buzz(new Buzz)) ==> digit
          if (includeBuzz) {
            Files.copy(resourcePath.resolve(s"Buzz.class"), dir.resolve("Buzz.class"))
            if (needIntercept) intercept[NoSuchMethodException](checkBuzz())
            else checkBuzz()
          } else {
            checkBuzz
          }
          Thunk(Bar.add(1, 2L)) ==> digit
        }

      val loader =
        ScalaChildFirstClassLoader(Seq.empty, Thread.currentThread.getContextClassLoader)
      Thread.currentThread.setContextClassLoader(loader)
      //test(6)
      test(6, includeBuzz = true)
      //      test(7)
      //      Class.forName("com.swoval.reflect.Buzz", false, loader)
      //      test(7, includeBuzz = true, needIntercept = false)
    }
  }

  override def utestBeforeEach(path: Seq[String]): Unit = {
    Thread.currentThread.setContextClassLoader(ScalaChildFirstClassLoader(initLoader))
  }
}
