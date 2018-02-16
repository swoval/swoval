package com.swoval.app

import utest._

object StaticMain {
  def run(args: Array[String]): Int = {
    args.head.toInt
  }
}

object MainRunnerTest extends TestSuite {
  val tests = Tests {
    'run - {
      MainRunner.run[Int](Array("--main", "com.swoval.app.StaticMain", "3")) ==> 3
      val handle = MainRunner.setup(Array("--main", "com.swoval.app.StaticMain", "3")).run[Int]
      class Foo {
        def run(args: Array[String]): Int = 3
      }
      println(handle.instance[Foo].run(Array("5")))
    }
  }
}
