package com.swoval.test

import scala.reflect.macros

package object compat {
  object blackbox {
    type Context = macros.blackbox.Context
  }
}
