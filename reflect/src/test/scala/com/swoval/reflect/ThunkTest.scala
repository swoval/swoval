package com.swoval.reflect

import java.io.File
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

trait Blah {
  def add(x: Int, y: Long): Long
}

object ThunkTest extends TestSuite {
  private def getChildFirstClassLoader: ChildFirstClassLoader = {
    try {
      Thread.currentThread.getContextClassLoader.asInstanceOf[ChildFirstClassLoader]
    } catch {
      case e: ClassCastException =>
        new ChildFirstClassLoader(Thread.currentThread.getContextClassLoader)
    }
  }
  override def utestBeforeEach(path: Seq[String]): Unit = {
    Thread.currentThread.setContextClassLoader(initLoader.copy())
  }
  private val pkg = "com.swoval.reflect"
  private val initLoader = getChildFirstClassLoader
  private val forked = !Files.exists(Paths.get("reflect").toAbsolutePath)
  private val resourcePath =
    Paths.get(s"${if (forked) "" else "reflect/"}src/test/resources").toAbsolutePath
  object Types {
    def x(b: Boolean): Int = 1
    def x(b: Byte): Int = 2
    def x(c: Char): Int = 3
    def x(d: Double): Int = 4
    def x(f: Float): Int = 5
    def x(i: Int): Int = 6
    def x(l: Long): Int = 7
    def x(s: Short): Int = 8
  }
  val tests = Tests {
    'run - {
      'thunk - {
        'reflectively - {
          'classArguments - {
            Thunk(Foo.buzz(new Buzz)) ==> 3
          }
          'primitiveArguments - {
            Thunk(Foo.bar(1)) ==> 2
          }
          'variableArguments - {
            val x = 3
            val y = 4L
            Thunk(Foo.add(x, y)) ==> x + y
          }
        }
      }
      'force - {
        'strict - {
          Thunk(Foo.add(1, 2), true) ==> 3
          Thunk(Foo.buzz(new Buzz), true) ==> 3
        }
        'reflective - {
          Thunk(Foo.buzz(new Buzz), false) ==> 3
          Thunk(Foo.add(1, 2), false) ==> 3
        }
      }
    }
    'boxing - {
      Thunk(Types.x(true)) ==> 1
      Thunk(Types.x(1.toByte)) ==> 2
      Thunk(Types.x('1')) ==> 3
      Thunk(Types.x(1.0)) ==> 4
      Thunk(Types.x(1.0f)) ==> 5
      Thunk(Types.x(1)) ==> 6
      Thunk(Types.x(1L)) ==> 7
      Thunk(Types.x(1.toShort)) ==> 8
    }

    'reload - {
      def test(digit: Long): Unit =
        withLoader { (path, l) =>
          val dir = Files.createDirectories(path.resolve("com/swoval/reflect"))
          Files.copy(resourcePath.resolve(s"Bar$$.class.$digit"), dir.resolve("Bar$.class"))
          Thunk(Bar.add(1, 2L)) ==> digit
        }

      test(6)
      test(7)
    }
    'find - {
      'existing - {
        withLoader((path, l) => Thunk(Bar.buzz(new Buzz)) ==> 3)
      }
      'replaced - {
        def withURLLoader[R](f: (Path, ChildFirstClassLoader) => R): R = withLoader { (p, l) =>
          val allURLs = System.getProperty("java.class.path").split(File.pathSeparator).map { p =>
            Paths.get(p).toUri.toURL
          } :+ p.toUri.toURL
          val loader = l.copy(allURLs)
          Thread.currentThread.setContextClassLoader(loader)
          f(p, l)
        }
        'notBlocked - {
          /*
           * If the urls contain the full class path, things will break here because the test object
           * will have been loaded with the app class loader which causes expressions like
           * new com.swoval.reflect.Buzz to use the app class loader rather than the url class
           * loader. This is a problem because the ChildFirstClassLoader will load the class using
           * its parent URLClassLoader findClass, which will load an incompatible duplicate version
           * of the class.
           */
          if (forked) {
            withURLLoader { (path, l) =>
              val dir = Files.createDirectories(path.resolve("com/swoval/reflect"))
              Files.copy(resourcePath.resolve(s"Buzz.class"), dir.resolve("Buzz.class"))
              intercept[NoSuchMethodException] {
                // There seems to be a bug in intercept that requires full qualification of Buzz
                Thunk(Bar.buzz(new com.swoval.reflect.Buzz))
              }
            }
          } else {
            println("Not running notBlocked test on embedded jvm.")
          }
        }
        'blocked - {
          withURLLoader { (path, l) =>
            /*
             * By adding an additional predicate to force the parent to load the Buzz class,
             * we avoid the problem described above.
             */
            Thread.currentThread.setContextClassLoader(l.copy(p =>
              new Predicates(n => p.getForceParent.test(n) || n == "com.swoval.reflect.Buzz",
                             p.getForceChild())))
            val dir = Files.createDirectories(path.resolve("com/swoval/reflect"))
            Files.copy(resourcePath.resolve(s"Buzz.class"), dir.resolve("Buzz.class"))
            Thunk(Bar.buzz(new com.swoval.reflect.Buzz)) ==> 3
          }
        }

      }
    }
  }

  private def withLoader[R](f: (Path, ChildFirstClassLoader) => R): R = {
    val dir = Files.createTempDirectory("reflective-thunk-test")
    val thread = Thread.currentThread
    val initLoader = getChildFirstClassLoader
    try {
      val loader = initLoader.copy(Array(dir.toUri.toURL))
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

}
