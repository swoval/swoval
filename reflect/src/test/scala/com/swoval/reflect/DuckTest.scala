package com.swoval.reflect

import utest._

object DuckTest extends TestSuite {
  val tests = Tests {
//    'singleMethod - {
//      'abstract - {
//        'specified - {
//          trait Foo {
//            def foo(): Int
//          }
//          object Bar {
//            def foo(): Int = 3
//          }
//          implicitly[Duck[Bar.type, Foo]].duck(Bar).foo() ==> 3
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
//    }
    'reflective - {
      'abstract - {
        'specified - {
          trait Foo {
            def foo(): Int
          }
          object Bar {
            def foo(): Int = 3
          }
          implicitly[Duck[Object, Foo]].duck(Bar).foo() ==> 3
        }
      }
    }
  }
}
