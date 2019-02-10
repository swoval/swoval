package com.swoval

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file._
import java.nio.file.attribute.{ BasicFileAttributes, FileTime }
import java.util.concurrent.{ BlockingQueue, TimeUnit }

import utest._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.util.Try

package object test {
  final val DEFAULT_TIMEOUT =
    Try(System.getProperty("swoval.test.timeout", "5").toInt).getOrElse(5).seconds

  implicit class PathOps(val path: Path) extends AnyVal {
    def getBytes: Array[Byte] = retry(Files.readAllBytes(path), classOf[NoSuchFileException])
    def createDirectory(): Path =
      retry(Files.createDirectory(path), classOf[FileAlreadyExistsException])
    def createDirectories(): Path =
      retry(Files.createDirectories(path), classOf[FileAlreadyExistsException])
    def createFile(): Path = retry(Files.createFile(path))
    def createTempFile(prefix: String): Path = {
      if (!path.isDirectory()) throw new NotDirectoryException(path.toString)
      retry(Files.createTempFile(path, prefix, ""))
    }
    def delete(): Boolean = retry(Files.deleteIfExists(path))
    def isRegularFile(): Boolean = retry(Files.isRegularFile(path))
    def isDirectory(): Boolean = retry(Files.isDirectory(path))
    def linkTo(target: Path): Path = retry(Files.createSymbolicLink(path, target))
    def read: String = new String(Files.readAllBytes(path))
    def write(content: String): Unit = retry(Files.write(path, content.getBytes))
    private def retry[T](f: => T, excludes: Class[_ <: IOException]*): T =
      retry(f, maxAttempts = 10, excludes: _*)
    private def retry[T](f: => T, maxAttempts: Int, excludes: Class[_ <: IOException]*): T = {
      @tailrec
      def impl(attempt: Int): T = {
        (try Right(f)
        catch {
          case e if !excludes.exists(_.isAssignableFrom(e.getClass)) && attempt < maxAttempts =>
            Left(e)
        }) match {
          case Right(t) => t
          case Left(_) =>
            platform.sleep(2.milliseconds)
            impl(attempt + 1)
        }
      }
      impl(0)
    }
    private def deleteImpl(path: Path): Boolean =
      retry(Files.deleteIfExists(path), maxAttempts = 10)
    def deleteRecursive(): Unit = {
      var deleted = false
      while (!deleted && Files.isDirectory(path)) {
        try {
          Files.walkFileTree(
            path,
            new FileVisitor[Path] {
              override def preVisitDirectory(dir: Path,
                                             attrs: BasicFileAttributes): FileVisitResult =
                FileVisitResult.CONTINUE

              override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
                deleteImpl(file)
                FileVisitResult.CONTINUE
              }

              override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE

              override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
                deleteImpl(dir)
                FileVisitResult.CONTINUE
              }
            }
          )
          deleted = true
        } catch {
          case _: AccessDeniedException =>
        }
      }
      deleteImpl(path)
    }
    def exists(): Boolean = retry(Files.exists(path), 10)
    def lastModified: Long =
      Try(Files.getLastModifiedTime(path).toMillis).getOrElse(java.lang.Long.MIN_VALUE)
    def name: String = path.getFileName.toString
    def parts: Seq[Path] = path.iterator.asScala.toIndexedSeq
    def renameTo(target: Path): Path = Files.move(path, target)
    def setLastModifiedTime(lastModified: Long): Unit =
      Files.setLastModifiedTime(path, FileTime.fromMillis(lastModified))
    def write(bytes: Array[Byte]): Path = Files.write(path, bytes)
    def write(content: String, charset: Charset = Charset.defaultCharset()): Path =
      Files.write(path, content.getBytes(charset))
  }

  /** Taken from shapeless */
  sealed trait <:!<[T, R]
  object <:!< {
    import scala.language.implicitConversions
    implicit def default[T, R]: <:!<[T, R] = new <:!<[T, R] {}
    implicit def alternative[T, R](implicit ev: T <:< R): <:!<[T, R] = new <:!<[T, R] {}
  }
  type NotFuture[T] = <:!<[T, Future[_]]
  object Implicits {
    implicit def executionContext: ExecutionContext = platform.executionContext
  }
  import Implicits.executionContext

  implicit class RichTraversable[T, M[_] <: Traversable[_]](val t: M[T]) {
    def ===(other: M[T]): Unit = {
      val tSet = t.toSet
      val oSet = other.toSet
      val tDiff = tSet diff oSet
      val oDiff = oSet diff tSet
      Seq(tDiff -> "extra", oDiff -> "missing") foreach {
        case (s, c) if s.nonEmpty =>
          val comp = truncate(s)
          println(
            s"The actual result had $c fields $comp compared to the expected result.\n" +
              s"Found: ${truncate(tSet)}\nExpected: ${truncate(oSet)} ")
          comp.toSet ==> Set.empty
        case _ =>
      }
      tSet ==> oSet
    }
  }
  private def truncate(set: Traversable[_]): Seq[String] = {
    if (set.size < 10) set.map(_.toString).toSeq.sorted
    else set.toSeq.map(_.toString).sorted.take(10) :+ "..."
  }
  implicit class RichOption[T](val t: Option[T]) {
    def ===(other: Option[T]): Unit = t ==> other
  }
  implicit class RichDuration(val d: Duration) extends AnyVal {
    private def toNanos = (d.toNanos - d.toMillis * 1e6).toInt
  }

  implicit class RichQueue[T](val q: BlockingQueue[T]) extends AnyVal {
    def poll(d: Duration): T = q.poll(d.toNanos, TimeUnit.NANOSECONDS)
    def pollFor(t: T, d: Duration): Unit = {
      val ceiling = System.nanoTime + d.toNanos
      @tailrec def impl(): Unit = {
        val d = (ceiling - System.nanoTime).nanos
        if ((d > 0.seconds) && (poll(d) != t)) impl()
      }
      impl()
    }
  }

  def using[C <: AutoCloseable, R: NotFuture](closeable: => C)(f: C => R): Future[R] = {
    val c = closeable
    val p = Promise[R]
    p.tryComplete(Try(f(c)))
    p.future.andThen { case _ => c.close() }
  }

  def usingAsync[C <: AutoCloseable, R](closeable: => C)(f: C => Future[R]): Future[R] = {
    val c = closeable
    f(c).andThen { case _ => c.close() }
  }

  object TestFiles {
    def withTempFile[R](dir: String)(f: String => Future[R]): Future[R] = {
      val (file: String, thread: Thread) = platform.createTempFile(dir, "file")
      f(file).andThen {
        case _ =>
          platform.delete(file)
          ShutdownHooks.remove(thread)
      }
    }

    def withTempFile[R](f: String => Future[R]): Future[R] =
      withTempDirectory { dir =>
        val (file: String, thread: Thread) = platform.createTempFile(dir, "file")
        f(file).andThen {
          case _ =>
            platform.delete(file)
            ShutdownHooks.remove(thread)
        }
      }

    def withTempDirectory[R](f: String => Future[R]): Future[R] = {
      val (dir: String, thread: Thread) = platform.createTempDirectory()
      withDirectory(dir, thread)(f(dir))
    }

    def withTempDirectory[R](dir: String)(f: String => Future[R]): Future[R] = {
      val (subDir: String, thread: Thread) = platform.createTempSubdirectory(dir)
      withDirectory(subDir, thread)(f(subDir))
    }

    def withDirectory[R](path: String, thread: Thread)(f: => Future[R]): Future[R] = {
      platform.mkdir(path)
      f.andThen {
        case _ =>
          platform.delete(path)
          ShutdownHooks.remove(thread)
      }
    }
  }
  def testOn(desc: String, platforms: Platform*)(tests: Any): Tests = macro Macros.testOnWithDesc
  def testOn(platforms: Platform*)(tests: Any): Tests = macro Macros.testOn
}
