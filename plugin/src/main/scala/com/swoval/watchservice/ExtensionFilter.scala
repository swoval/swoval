package com.swoval.watchservice

import java.io.File

import sbt.io.FileFilter

case class ExtensionFilter(extensions: String*) extends FileFilter {
  val _extensions = extensions.toSet
  override def accept(pathname: File): Boolean = {
    val fileName = pathname.toString
    val ext = fileName.lastIndexOf('.') match {
      case -1 => ""
      case i  => fileName.substring(i + 1)
    }
    _extensions(ext)
  }
}
