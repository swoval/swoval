package com.swoval.functional

import utest._

object EitherTest extends TestSuite {
  implicit class EitherOps[L, R](val either: Either[L, R]) extends AnyVal {
    def left(): Either.Left[L, R] = Either.leftProjection[L, R](either)
    def right(): Either.Right[L, R] = Either.rightProjection[L, R](either)
  }
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
