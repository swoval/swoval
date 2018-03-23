package com.swoval.watchservice

import java.io.File

case class ExtensionFilter(extensions: String*) extends sbt.FileFilter {
  val _extensions = extensions.toSet
  override def accept(pathname: File): Boolean = {
    val fileName = pathname.toString
    val ext = fileName.lastIndexOf('.') match {
      case -1 => ""
      case i  => fileName.substring(i + 1)
    }
    _extensions(ext)
  }
  override def &&(other: sbt.FileFilter) = new ExtensionFilter(extensions: _*) {
    override def accept(pathname: File): Boolean = super.accept(pathname) && other.accept(pathname)
    override def toString: String =
      s"ExtensionFilter(${extensions.map(e => s"*.$e") mkString ", "}) && $other"
  }
}
