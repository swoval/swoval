package com.swoval

import sbt._

object Commands {
  def remaining(state: State): Seq[String] = state.remainingCommands
  def append(state: State, command: String) =
    state.copy(remainingCommands = command +: state.remainingCommands)
}

object Settings {
  def defaultScalaVersion = Build.scala210
  def append(settings: Seq[Setting[_]], extracted: Extracted, state: State) =
    extracted.append(settings, state)
}
