package com.swoval.test

sealed trait Platform
case object MacOS extends Platform { override def toString = "MacOS" }
case object Linux extends Platform { override def toString = "Linux" }
case object Windows extends Platform { override def toString = "Windows" }
