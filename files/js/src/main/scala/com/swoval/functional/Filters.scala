package com.swoval.functional

object Filters {

  val AllPass: Filter[Any] = new Filter[Any]() {
    override def accept(o: AnyRef): Boolean = true
  }

}
