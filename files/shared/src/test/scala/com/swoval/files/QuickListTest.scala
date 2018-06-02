package com.swoval.files

import java.nio.file.{
  AccessDeniedException,
  FileSystemLoopException,
  Files,
  NoSuchFileException,
  NotDirectoryException,
  Path => JPath
}

import utest._
import com.swoval.test._
import com.swoval.files.test._

import scala.collection.JavaConverters._
import QuickListTest.QuickListOps

object QuickListTest {
  implicit class QuickListOps(val ql: QuickLister) extends AnyVal {
    def ls(path: JPath, recursive: Boolean, followLinks: Boolean = false): Seq[JPath] =
      ql.list(path, if (recursive) java.lang.Integer.MAX_VALUE else 1, followLinks)
        .asScala
        .map(_.toPath().normalize())
    def ls(path: JPath, depth: Int): Seq[JPath] = ls(path, depth, followLinks = true)
    def ls(path: JPath, depth: Int, followLinks: Boolean): Seq[JPath] =
      ql.list(path, depth, followLinks).asScala.map(_.toPath())
  }
}
class QuickListTest(quickLister: QuickLister, run: Boolean) extends TestSuite {
  // This is because the utest scala 2.10 macro expansion can't handle the `===` expression
  def check(l: Traversable[JPath], r: Traversable[JPath]): Unit = l === r
  val tests = if (run) Tests {
    'simple - withTempFileSync { file =>
      check(quickLister.ls(file.getParent, recursive = true), Seq(file))
    }
    'mixed - withTempFile { file =>
      val base = file.getParent
      withTempDirectorySync(file.getParent) { subdir =>
        check(quickLister.ls(base, recursive = true), Set(file, subdir))
      }
    }
    'depth - withTempFile { file =>
      val base = file.getParent
      withTempDirectory(file.getParent) { subdir =>
        withTempDirectory(subdir) { nestedSubdir =>
          withTempFileSync(nestedSubdir) { nestedFile =>
            val files = quickLister.ls(base, 1)
            check(files, Set(file, subdir))
            val filesWithDepthTwo = quickLister.ls(base, depth = 2)
            check(filesWithDepthTwo, Set(file, subdir, nestedSubdir))
            val filesWithDepthThree = quickLister.ls(base, depth = 3)
            check(filesWithDepthThree, Set(file, subdir, nestedSubdir, nestedFile))
          }
        }
      }
    }
    'links - {
      'simple - withTempFile { file =>
        val base = file.getParent
        withTempFileSync { otherFile =>
          val link = Files.createTempFile(base, "link", "")
          Files.delete(link)
          Files.createSymbolicLink(link, otherFile)
          check(quickLister.ls(base, recursive = false), Set(file, link))
          check(quickLister.ls(base, recursive = false, followLinks = true), Set(file, link))
        }
      }
      'nested - withTempFile { file =>
        val base = file.getParent
        withTempFile { otherFile =>
          withTempDirectorySync { otherDir =>
            val nestedLink =
              Files.createSymbolicLink(otherDir.resolve("nested-link"), otherFile.getParent)
            val link = Files.createSymbolicLink(base.resolve("link"), nestedLink)
            check(quickLister.ls(base, recursive = false), Set(file, link))
            check(quickLister.ls(base, recursive = true, followLinks = true),
                  Set(file, link, link.resolve(otherFile.getFileName)))
          }
        }
      }
      'directory - {
        'nofollow - withTempDirectory { dir =>
          withTempFileSync { file =>
            val otherDir = file.getParent
            val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
            check(quickLister.ls(dir, recursive = true), Set(link))
          }
        }
        'follow - withTempDirectory { dir =>
          withTempFileSync { file =>
            val otherDir = file.getParent
            val link = Files.createSymbolicLink(dir.resolve("link"), otherDir)
            check(quickLister.ls(dir, recursive = true, followLinks = true),
                  Set(link, link.resolve(file.getFileName)))
          }
        }
      }
    }
    'broken - withTempDirectorySync { dir =>
      Files.createSymbolicLink(dir.resolve("link"), dir.resolve("broken"))
      intercept[NoSuchFileException](quickLister.ls(dir, recursive = true, followLinks = true))
    }
    'exceptions - {
      'notDirectory - withTempFileSync { file =>
        intercept[NotDirectoryException](quickLister.ls(file, 1))
      }
      'notExists - withTempDirectorySync { dir =>
        intercept[NoSuchFileException](quickLister.ls(dir.resolve("foo"), recursive = true))
      }
      'accessDenied - withTempFileSync { file =>
        if (!Platform.isWin) {
          val parent = file.getParent()
          parent.toFile.setReadable(false)
          try intercept[AccessDeniedException](quickLister.ls(parent, recursive = true))
          finally parent.toFile.setReadable(true)
        }
      }
      'accessDeniedChild - withTempDirectory { dir =>
        withTempDirectory(dir) { subdir =>
          withTempFileSync(subdir) { file =>
            if (!Platform.isWin) {
              subdir.toFile.setReadable(false)
              try intercept[AccessDeniedException](quickLister.ls(dir, recursive = true))
              finally subdir.toFile.setReadable(true)
            }
          }
        }
      }
      'loop - withTempDirectory { dir =>
        withTempDirectorySync { otherDir =>
          val link1 = Files.createSymbolicLink(dir.resolve("link"), otherDir)
          val link2 = Files.createSymbolicLink(otherDir.resolve("link"), dir)
          intercept[FileSystemLoopException](quickLister.ls(dir, 3, followLinks = true))
        }
      }
    }
  } else Tests {}
}
object NativeQuickListTest extends QuickListTest(new NativeQuickLister, Platform.isJVM)
object NioQuickListTest extends QuickListTest(new NioQuickLister, true)
