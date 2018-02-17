package com.swoval.reflect

import utest._

object DuckTest extends TestSuite {
  def benchmark[R](n: Int, tag: String = "")(f: => R): Unit = {
    val start = System.nanoTime
    (0 to n) foreach (_ => f)
    val elapsed = (System.nanoTime - start) / 1e3 / n
    println(f"Took $elapsed%#4f us per iteration to run${if (tag.nonEmpty) s" $tag" else ""}")
  }
  val tests = Tests {
    'singleMethod - {
//      'abstract - {
//        'specified - {
//          trait Foo {
//            def foo(): Int
//          }
//          object Bar {
//            def foo(): Int = 3
//          }
//          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 3
//          println(implicitly[WeakDuck[Bar.type, Foo]])
//          WeakDuck.default[Bar.type, Foo].duck(Bar).foo() ==> 3
//        }
//      }
//      'default - {
//        trait Foo {
//          def foo(): Int = 3
//        }
//        'specified - {
//          object Bar {
//            def foo(): Int = 5
//          }
//          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 5
//        }
//        'unspecified - {
//          object Bar
//          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 3
//        }
//      }
    }
    'reflective - {
      'abstract - {
        'specified - {
          trait Foo {
            def foo(i: Int, y: Long): Long
          }
          abstract class Blah {
            def foo(i: Int, y: Long): Long = i + y
          }
          object Bar extends Blah
          import Duck.features.AllowWeakConversions
          implicitly[Duck[Object, Foo]].duck(Bar).foo(1, 3) ==> 4
//          val foo: Foo = implicitly[Duck[Object, Foo]].duck(Bar)
//          val n = 100000
//          benchmark(n, "bar")(Bar.foo(1, 3))
//          benchmark(n, "foo")(foo.foo(1, 3))
//          benchmark(n, "foo")(foo.foo(1, 3))
//          benchmark(n, "bar")(Bar.foo(1, 3))
//          benchmark(n, "foo")(foo.foo(1, 3))
//          benchmark(n, "bar")(Bar.foo(1, 3))
        }
      }
    }
  }
}
