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
  def add(x: Int, y: Long): Long = ???
}

object ThunkTest extends TestSuite {
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
      'strict - {
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

    def withLoader[R](f: (Path, ChildFirstClassLoader) => R) = {
      val dir = Files.createTempDirectory("reflective-thunk-test")
      try {
        f(dir, ChildFirstClassLoader(Seq(dir.toUri.toURL)))
      } finally {
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
      def test(digit: Long): Unit = withLoader { (path, l) =>
        implicit val _: ChildFirstClassLoader = l
        val dir = Files.createDirectories(path.resolve("com/swoval/reflect"))
        Files.copy(resourcePath.resolve(s"Bar$$.class.$digit"), dir.resolve("Bar$.class"))
        Thunk(Bar.add(1, 2L)) ==> digit
      }
      test(6)
      test(7)
    }
  }
}
