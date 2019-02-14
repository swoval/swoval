package com.swoval.logging

import com.swoval.logging.Loggers.Level
import utest._

object LoggingSpec extends TestSuite {
  implicit class LevelOps(val level: Level) extends AnyVal {
    def <(that: Level): Boolean = level.compareTo(that) < 0
    def <=(that: Level): Boolean = level.compareTo(that) <= 0
    def >(that: Level): Boolean = level.compareTo(that) > 0
    def >=(that: Level): Boolean = level.compareTo(that) >= 0
  }
  val tests: Tests = Tests {
    'level - {
      'ordering - {
        assert(Level.VERBOSE < Level.DEBUG)
        assert(Level.VERBOSE < Level.INFO)
        assert(Level.VERBOSE < Level.WARN)
        assert(Level.VERBOSE < Level.ERROR)
        assert(Level.DEBUG < Level.INFO)
        assert(Level.DEBUG < Level.WARN)
        assert(Level.DEBUG < Level.ERROR)
        assert(Level.INFO < Level.WARN)
        assert(Level.INFO < Level.ERROR)
        assert(Level.WARN < Level.ERROR)

        assert(Level.VERBOSE <= Level.VERBOSE)
        assert(Level.VERBOSE <= Level.DEBUG)
        assert(Level.VERBOSE <= Level.INFO)
        assert(Level.VERBOSE <= Level.WARN)
        assert(Level.VERBOSE <= Level.ERROR)
        assert(Level.DEBUG <= Level.DEBUG)
        assert(Level.DEBUG <= Level.INFO)
        assert(Level.DEBUG <= Level.WARN)
        assert(Level.DEBUG <= Level.ERROR)
        assert(Level.INFO <= Level.INFO)
        assert(Level.INFO <= Level.WARN)
        assert(Level.INFO <= Level.ERROR)
        assert(Level.WARN <= Level.WARN)
        assert(Level.WARN <= Level.ERROR)
        assert(Level.ERROR <= Level.ERROR)
      }
    }
  }
}
