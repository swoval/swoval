package java.nio.file

class FileVisitResult private ()

object FileVisitResult {
  val CONTINUE = new FileVisitResult
  val TERMINATE = new FileVisitResult
  val SKIP_SUBTREE = new FileVisitResult
  val SKIP_SIBLINGS = new FileVisitResult
}
