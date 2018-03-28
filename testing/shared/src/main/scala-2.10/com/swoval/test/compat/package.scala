package com.swoval.test

import scala.reflect.macros

package object compat {
  object blackbox {
    type Context = macros.Context
  }
  object TermName {
    def unapply[C <: macros.Context](name: C#Name): Option[String] =
      Some(name.toString)
  }
}
