package com.swoval.watchservice

import sbt._

import scala.collection.mutable

object Sources {
  def apply(task: Def.ScopedKey[_],
            state: State,
            logger: Logger,
            visited: mutable.HashSet[String]): Seq[SourcePath] = {
    val extracted = Project.extract(state)
    val structure = extracted.structure
    val currentRef =
      structure.allProjectRefs
        .find(_ == task.scope.project.fold(p => p, ThisBuild, ThisBuild))
        .getOrElse(extracted.currentRef)
    def impl(ref: ProjectRef): Seq[SourcePath] = {
      if (visited.add(ref.project)) {
        val current = structure.allProjects.find(_.id == ref.project)
        current.toSeq.flatMap(
          _.dependencies
            .filterNot(d => visited.contains(d.project.project))
            .flatMap(configuration(structure))
            .flatMap(_.project.fold({
              case (p: ProjectRef) => impl(p)
              case _               => Nil
            }, Nil, Nil))) ++ Compat.filter {
          val key = Def.ScopedKey(task.scope in ref, Keys.watchSources.key)
          val nil: Seq[Compat.WatchSource] = Nil
          EvaluateTask(structure, key, state, ref).fold(nil)(_._2.toEither.right.getOrElse(nil))
        }
      } else {
        Nil
      }
    }
    impl(currentRef).distinct
  }
  private def configuration(structure: Compat.Structure) = (dep: ClasspathDep[ProjectRef]) => {
    def scoped(conf: Configuration) = Compat.global in dep.project in conf
    def scopedKey(conf: ConfigKey) = Compat.global in dep.project in conf
    lazy val default: Seq[Scope] = structure.allProjects.flatMap {
      case c if c.id == dep.project.project => c.configurations.map(scoped)
      case _                                => Seq.empty
    }
    dep.configuration match {
      case Some(c) =>
        c.split(";") match {
          case confs if confs.nonEmpty =>
            confs.toSeq.map(n => scopedKey(ConfigKey(n.split("->").head)))
          case _ => default
        }
      case None => default
    }
  }
}
