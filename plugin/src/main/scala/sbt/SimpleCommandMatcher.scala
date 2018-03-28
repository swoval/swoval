package sbt

object SimpleCommandMatcher {
  def nameMatches(name: String): Command => Boolean = {
    case sc: SimpleCommand => sc.name == name
    case _                 => false
  }
}
