package com.swoval.files

import java.nio.file.{ Path, Paths }
import java.util

import com.swoval.files.TestHelpers._
import com.swoval.files.test._
import com.swoval.functional.Filter
import com.swoval.functional.Filters.AllPass
import com.swoval.runtime.Platform
import com.swoval.test._
import utest._

import scala.collection.JavaConverters._
import scala.concurrent.Future

object FileTreeViewTest {
  implicit class RepositoryOps[T <: AnyRef](val d: DirectoryView) {
    def ls(path: Path, recursive: Boolean, filter: Filter[_ >: TypedPath]): Seq[Path] =
      d.list(path, if (recursive) Integer.MAX_VALUE else 0, filter).asScala.map(_.getPath)
    def ls(path: Path, depth: Int, filter: Filter[_ >: TypedPath]): Seq[Path] =
      d.list(path, depth, filter).asScala.map(_.getPath)
    def ls(recursive: Boolean, filter: Filter[_ >: TypedPath]): Seq[Path] =
      d.list(if (recursive) Integer.MAX_VALUE else 0, filter).asScala.map(_.getPath)
  }
}
import com.swoval.files.FileTreeViewTest._
class FileTreeViewTest(newFileTreeView: (Path, Int, Boolean) => DirectoryView) extends TestSuite {
  def newFileTreeView(path: Path): DirectoryView = newFileTreeView(path, Integer.MAX_VALUE, false)
  def newFileTreeView(path: Path, maxDepth: Int): DirectoryView =
    newFileTreeView(path, maxDepth, true)

  def pathFilter(f: TypedPath => Boolean): Filter[TypedPath] = (tp: TypedPath) => f(tp)

  object list {
    def empty: Future[Unit] = withTempDirectorySync { dir =>
      newFileTreeView(dir).ls(dir, recursive = true, AllPass) === Seq.empty[Path]
    }
    object files {
      def parent: Future[Unit] =
        withTempFileSync { file =>
          val parent = file.getParent
          newFileTreeView(parent).ls(parent, recursive = true, AllPass) === Seq(file)
        }
      def directly: Future[Unit] = withTempFileSync { file =>
        val parent = file.getParent
        newFileTreeView(parent).ls(file, -1, AllPass) === Seq(file)
        newFileTreeView(parent).ls(file, 0, AllPass) === Nil
      }
    }
    def resolution: Future[Unit] = withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        withTempFileSync(subdir) { f =>
          def parentEquals(dir: Path): Filter[TypedPath] =
            (tp: TypedPath) => tp.getPath.getParent == dir
          val directory = newFileTreeView(dir)
          directory.ls(recursive = true, parentEquals(dir)) === Seq(subdir)
          directory.ls(recursive = true, parentEquals(subdir)) === Seq(f)
          directory.ls(recursive = true, AllPass) === Seq(subdir, f)
        }
      }
    }
    object directories {
      def nonRecursive: Future[Unit] = withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { _ =>
              newFileTreeView(dir).ls(recursive = false, AllPass) === Set(f, subdir)
            }
          }
        }
      }
      def recursive: Future[Unit] = withTempDirectory { dir =>
        withTempFile(dir) { f =>
          withTempDirectory(dir) { subdir =>
            withTempFileSync(subdir) { f2 =>
              newFileTreeView(dir).ls(recursive = true, AllPass) === Set(f, f2, subdir)
            }
          }
        }
      }
    }
    def subdirectories: Future[Unit] = withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        withTempFileSync(subdir) { f =>
          newFileTreeView(dir).ls(subdir, recursive = true, AllPass) === Seq(f)
          newFileTreeView(dir).ls(Paths.get(s"$subdir.1"), recursive = true, AllPass) === Nil
        }
      }
    }
    def filter: Future[Unit] = withTempDirectory { dir =>
      withTempFile(dir) { f =>
        withTempDirectorySync(dir) { subdir =>
          newFileTreeView(dir)
            .ls(recursive = true, pathFilter(!(_: TypedPath).isDirectory)) === Seq(f)
          newFileTreeView(dir)
            .ls(recursive = true, pathFilter((_: TypedPath).isDirectory)) === Seq(subdir)
        }
      }
    }
  }
  def recursive: Future[Unit] = withTempDirectory { dir =>
    withTempDirectory(dir) { subdir =>
      withTempFileSync(subdir) { f =>
        assert(f.exists)
        newFileTreeView(subdir).ls(subdir, recursive = true, AllPass) === Seq(f)
        newFileTreeView(dir, 0).ls(dir, recursive = true, AllPass) === Seq(subdir)
        newFileTreeView(dir).ls(dir, recursive = true, AllPass) === Set(subdir, f)
        newFileTreeView(dir).ls(dir, recursive = false, AllPass) === Seq(subdir)
      }
    }
  }
  object depth {
    def nonnegative: Future[Unit] = withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        withTempFileSync(subdir) { file =>
          newFileTreeView(dir, 0).ls(dir, recursive = true, AllPass) === Set(subdir)
          newFileTreeView(dir, 1).ls(dir, recursive = true, AllPass) === Set(subdir, file)
        }
      }
    }
    object negative {
      def ls(fileTreeView: FileTreeView, file: Path): Seq[Path] =
        fileTreeView.list(file, -1, AllPass).asScala.map(_.getPath)
      def file: Future[Unit] = withTempFileSync { file =>
        newFileTreeView(file, -1)
          .list(file, -1, AllPass)
          .asScala
          .toIndexedSeq
          .map(_.getPath) === Seq(file)
      }
      def directory: Future[Unit] = withTempDirectorySync { dir =>
        newFileTreeView(dir, -1).ls(dir, -1, AllPass) === Seq(dir)
      }
      def parameter: Future[Unit] = withTempFileSync { file =>
        val dir = file.getParent
        val directory = newFileTreeView(dir, Integer.MAX_VALUE)
        directory.list(dir, -1, AllPass).asScala.map(_.getPath) === Seq(dir)
        directory.list(dir, 0, AllPass).asScala.map(_.getPath) === Seq(file)
      }
    }
  }
  object init {
    def accessDenied: Future[Unit] =
      if (!Platform.isWin) withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { file =>
            subdir.toFile.setReadable(false)
            try {
              val directory = newFileTreeView(dir)
              directory.ls(dir, recursive = true, AllPass) === Seq(subdir)
            } finally {
              subdir.toFile.setReadable(true)
            }
          }
        }
      } else { Future.successful(()) }
  }
  object symlinks {
    def file: Future[Unit] = withTempFileSync { file =>
      val parent = file.getParent
      val link = parent.resolve("link") linkTo file
      newFileTreeView(parent).ls(parent, recursive = true, AllPass) === Set(file, link)
    }
    def directory: Future[Unit] = withTempDirectory { dir =>
      withTempDirectorySync { otherDir =>
        val link = dir.resolve("link") linkTo otherDir
        val file = otherDir.resolve("file").createFile()
        val dirFile = dir.resolve("link").resolve("file")
        newFileTreeView(dir, Integer.MAX_VALUE, true).ls(dir, recursive = true, AllPass) === Set(
          link,
          dirFile)
      }
    }
    object loop {
      def initial: Future[Unit] = withTempDirectory { dir =>
        withTempDirectorySync { otherDir =>
          val dirToOtherDirLink = dir.resolve("other") linkTo otherDir
          val otherDirToDirLink = otherDir.resolve("dir") linkTo dir
          newFileTreeView(dir, Integer.MAX_VALUE, true)
            .ls(dir, recursive = true, AllPass) === Set(dirToOtherDirLink,
                                                        dirToOtherDirLink.resolve("dir"))
        }
      }
    }

  }
  val tests = Tests {
    'list - {
      'empty - list.empty
      'files - {
        'parent - list.files.parent
        'directly - list.files.directly
      }
      'resolution - list.resolution
      'directories {
        'nonRecursive - list.directories.nonRecursive
        'recursive - list.directories.recursive
      }
      'subdirectories - list.subdirectories
      'filter - list.filter
    }
    'recursive - recursive
    'depth - {
      'nonnegative - depth.nonnegative
      'negative - {
        'file - depth.negative.file
        'directory - depth.negative.directory
        'parameter - depth.negative.parameter
      }
    }
    'init - {
      'accessDenied - init.accessDenied
    }
    'symlinks - {
      'file - symlinks.file
      'directory - symlinks.directory
      'loop - symlinks.loop.initial
    }
  }
}
object DirectoryFileTreeViewTest extends FileTreeViewTest(FileTreeViews.cached)
object DefaultFileTreeViewTest
    extends FileTreeViewTest((path, depth, follow: Boolean) => {
      new DirectoryView {
        private val view = FileTreeViews.getDefault(follow, true)
        override def getPath: Path = path
        override val getTypedPath: TypedPath = TypedPaths.get(path)
        override def list(maxDepth: Int, filter: Filter[_ >: TypedPath]): util.List[TypedPath] = {
          val actualDepth = if (maxDepth > depth) depth else maxDepth
          view.list(path, actualDepth, filter)
        }
        override def getMaxDepth: Int = depth
        override def list(path: Path,
                          maxDepth: Int,
                          filter: Filter[_ >: TypedPath]): util.List[TypedPath] = {
          if (path.startsWith(getPath)) {
            val distance = getPath.relativize(path).getNameCount - 1
            val actualDepth =
              if (maxDepth < Int.MaxValue - distance) maxDepth + distance else maxDepth
            val d = if (actualDepth > depth) depth else actualDepth
            view.list(path, d, filter)
          } else {
            util.Collections.emptyList()
          }
        }
        override def close(): Unit = {}
      }
    })
object NioFileTreeViewTest
    extends FileTreeViewTest(
      (path: Path, depth: Int, followLinks: Boolean) =>
        new CachedDirectoryImpl[Path](TypedPaths.get(path),
                                      (tp: TypedPath) => tp.getPath,
                                      depth,
                                      AllPass,
                                      followLinks,
                                      FileTreeViews.getNio(followLinks)).init())
