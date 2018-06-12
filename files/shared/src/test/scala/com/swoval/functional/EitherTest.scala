package com.swoval.functional

import utest._

object EitherTest extends TestSuite {
  override def tests: Tests = Tests {
    'exceptions - {
      'left - {
        intercept[Either.NotLeftException](Either.right(1).left())
        ()
      }
      'right - {
        intercept[Either.NotRightException](Either.left(1).right())
        ()
      }
    }
    'type - {
      'left - {
        val left = Either.left(1)
        assert(left.isLeft)
        assert(!left.isRight)
      }
      'right - {
        val right = Either.right(1)
        assert(right.isRight)
        assert(!right.isLeft)
      }
    }
  }
}
