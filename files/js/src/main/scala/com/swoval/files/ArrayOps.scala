package com.swoval.files

object ArrayOps {

  def find[T](array: Seq[T], filter: com.swoval.functional.Filter[T]): T =
    array.find(filter.accept).getOrElse(null.asInstanceOf[T])

  def contains[T](array: Seq[T], el: T): Boolean = {
    var result: Boolean = false
    var i: Int = 0
    while (!result && i < array.length) {
      if (array(i) == el) result = true
      i += 1
    }
    result
  }

}
