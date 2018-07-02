package java.nio.file

object StandardWatchEventKinds {
  final val OVERFLOW: WatchEvent.Kind[AnyRef] =
    new StdWatchEventKind[AnyRef]("OVERFLOW", classOf[AnyRef])
  final val ENTRY_CREATE: WatchEvent.Kind[Path] =
    new StdWatchEventKind("ENTRY_CREATE", classOf[Path])
  final val ENTRY_DELETE: WatchEvent.Kind[Path] =
    new StdWatchEventKind("ENTRY_DELETE", classOf[Path])
  final val ENTRY_MODIFY: WatchEvent.Kind[Path] =
    new StdWatchEventKind("ENTRY_MODIFY", classOf[Path])

  private class StdWatchEventKind[T](_name: String, tpe: Class[T]) extends WatchEvent.Kind[T] {
    override def name(): String = _name
    override def `type`(): Class[T] = tpe
    override def toString: String = _name
    override def equals(o: Any): Boolean = o match {
      case e: WatchEvent.Kind[_] => e.name() == this.name()
      case _                     => false
    }
    override def hashCode(): Int = _name.hashCode();
  }
}
