package com.swoval.reflect

import utest._

trait BlahBaz {
  def foo: Int
}

object DuckTest extends TestSuite {
  def benchmark[R](n: Int, tag: String = "")(f: => R): Unit = {
    val start = System.nanoTime
    (0 to n) foreach (_ => f)
    val elapsed = (System.nanoTime - start) / 1e3 / n
    println(f"Took $elapsed%#4f us per iteration to run${if (tag.nonEmpty) s" $tag" else ""}")
  }
  val tests = Tests {
    'singleMethod - {
      'abstract - {
        trait Foo {
          def foo(): Int
        }
        'specified - {
          object Bar {
            def foo(): Int = 3
          }
          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 3
        }
        'unspecified - {
          object Bar
          compileError("implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 3")
        }
      }
      'default - {
        trait Foo {
          def foo(): Int = 3
        }
        'specified - {
          object Bar {
            def foo(): Int = 5
          }
          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 5
        }
        'unspecified - {
          object Bar
          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 3
        }
      }
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
          import Duck.features.AllowReflection
          implicitly[Duck[Object, Foo]].duck(Bar).foo(1, 3) ==> 4
          import Duck._
        }
        'unspecified - {
          trait Foo {
            def close(): Unit
          }
          object Bar
          'useDefault - {
            import Duck.features.AllowWeakReflection
            implicitly[Duck[Object, Foo]].duck(Bar).close() ==> {}
          }
          'crashAtRuntime - {
            import Duck.features.AllowReflection
            val foo = implicitly[Duck[Object, Foo]].duck(Bar)
            intercept[IllegalArgumentException](foo.close() ==> {})
          }
          'delegate - {
            var x = 0
            trait Foo {
              def close(): Unit = { x = 1 }
            }
            object Bar
            'reflect - {
              import Duck.features.AllowReflection
              implicitly[Duck[Object, Foo]].duck(Bar).close() ==> {}
              x ==> 1
            }
            'weak - {
              import Duck.features.AllowWeakReflection
              implicitly[Duck[Object, Foo]].duck(Bar).close() ==> {}
              x ==> 1
            }
          }
        }
      }
      'override - {
        trait Foo {
          def foo(i: Int, y: Long): Long = y - i
        }
        'specified - {
          object Bar {
            def foo(i: Int, y: Long): Long = i + y
          }
          import Duck.features.AllowReflection
          implicitly[Duck[Object, Foo]].duck(Bar).foo(1, 3) ==> 4
        }
        'unspecified - {
          object Bar
          import Duck.features.AllowReflection
          implicitly[Duck[Object, Foo]].duck(Bar).foo(1, 3) ==> 2L
        }
      }
    }
  }
}
