package com.swoval.functional

object Filters {

  val AllPass: Filter[AnyRef] = new Filter[AnyRef]() {
    override def accept(o: AnyRef): Boolean = true
  }

}
