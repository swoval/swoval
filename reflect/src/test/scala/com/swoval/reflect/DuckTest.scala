package com.swoval.reflect

import utest._

object DuckTest extends TestSuite {
  import Duck.DuckOps
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
          Bar.duckType[Foo].foo() ==> 3
        }
        'unspecified - {
          object Bar
          compileError("Bar.duckType[Foo].foo() ==> 3")
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
          Bar.duckType[Foo].foo() ==> 5
        }
        'unspecified - {
          object Bar
          Bar.duckType[Foo].foo() ==> 3
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
          Bar.weakDuckType[Foo].foo(1, 3) ==> 4
        }
        'unspecified - {
          trait Foo {
            def close(): Unit
          }
          object Bar
          'useDefault - {
            import Duck.features.AllowWeakReflection
            Bar.weakDuckType[Foo].close() ==> {}
          }
          'crashAtRuntime - {
            import Duck.features.AllowReflection
            val foo = Bar.weakDuckType[Foo]
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
              Bar.weakDuckType[Foo].close() ==> {}
              x ==> 1
            }
            'weak - {
              import Duck.features.AllowWeakReflection
              Bar.weakDuckType[Foo].close() ==> {}
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
          Bar.weakDuckType[Foo].foo(1, 3) ==> 4
        }
        'unspecified - {
          object Bar
          import Duck.features.AllowReflection
          Bar.weakDuckType[Foo].foo(1, 3) ==> 2L
        }
      }
    }
  }
}
