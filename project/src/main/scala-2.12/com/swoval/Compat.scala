package com.swoval

import sbt._

object Commands {
  def remaining(state: State): Seq[String] = state.remainingCommands.map(_.commandLine)
  def append(state: State, command: String) =
    state.copy(remainingCommands = Exec(command, None) +: state.remainingCommands)
}
object Settings {
  def defaultScalaVersion = Build.scala212
  def append(settings: Seq[Setting[_]], extracted: Extracted, state: State): State =
    extracted.appendWithoutSession(settings, state)
}
