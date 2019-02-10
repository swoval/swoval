package com.swoval.files

import java.nio.file.{ Path, Paths }

import com.swoval.runtime.Platform
import com.swoval.test._
import utest._

import scala.util.Random

object PathTest extends TestSuite {
  private val random: Random = new Random()
  private val root: String = if (Platform.isWin) "C:" else ""
  def get(parts: String*): Path = {
    require(parts.nonEmpty, "You must provided at least one part of a path name")
    parts.head match {
      case "" => Paths.get(s"/${parts.tail.head}", parts.tail.tail: _*)
      case h  => Paths.get(h, parts.tail: _*)
    }
  }
  def sep: String = java.io.File.separator
  override val tests = Tests {
    'resolve - {
      'relative {
        'simple - {
          val parts = Seq("foo", "bar", "baz")
          val base = get(parts: _*)
          val subdir = get("buzz")
          val resolved = base.resolve(subdir)
          resolved.toString ==> (parts :+ "buzz").mkString(sep)
        }
        'nested - {
          val base = get(root, "foo")
          val relativeSubdir = base.relativize(get(root, "foo", "bar"))
          val relativeFile = relativeSubdir.relativize(get("bar", "baz"))
          base.resolve(relativeSubdir.resolve(relativeFile)).toString ==>
            Seq(root, "foo", "bar", "baz").mkString(sep)
        }
      }
      'absolute {
        'child - {
          val base = get("", "foo", "bar", "baz")
          val subdir = get(s"", "foo", "bar", "baz", "buzz")
          val resolved = base.resolve(subdir)
          resolved.toString ==> Seq("", "foo", "bar", "baz", "buzz").mkString(sep)
        }
        'unrelated - {
          val base = get(s"", "foo", "bar", "baz")
          val subdir = get(s"", "ok", "foo", "bar", "baz", "buzz")
          val resolved = base.resolve(subdir)
          resolved.toString ==> Seq("", "ok", "foo", "bar", "baz", "buzz").mkString(sep)
        }
      }
    }
    'relativize - {
      'child - {
        val base = get("", "foo", "bar", "baz")
        val subdir = get("", "foo", "bar", "baz", "buzz")
        base.relativize(subdir).toString ==> "buzz"
        base.resolve(subdir).toString ==> subdir.toString
      }
      'unrelated - {
        val base = get("", "foo", "bar", "baz")
        val subdir = get("", "ok", "foo", "bar", "baz", "buzz")
        base.relativize(subdir).toString ==> s"..$sep..$sep..$sep${subdir.parts.mkString(sep)}"
      }
    }
    'parts - {
      'absolute - {
        val path = get("", "foo", "bar", "baz")
        path.parts.map(_.toString) === Seq("foo", "bar", "baz")
      }
      'relative - {
        val parent = get("", "foo", "bar", "baz", "")
        val child = get("", "foo", "bar", "baz", "buzz")
        val relative = parent.relativize(child)
        relative.parts.map(_.toString) === Seq("buzz")
      }
    }
    'getBytes - {
      test.withTempFileSync { f =>
        f.write("foo")
        f.getBytes ==> "foo".getBytes
      }
    }
    'createDirectory - {
      'absolute - {
        val path = get(Platform.tmpDir).resolve(s"foo${random.nextInt}")
        path.delete()
        try {
          path.createDirectory() ==> path
          assert(path.exists)
        } finally path.delete()
      }
      'relative - {
        val path = get(s"foo${random.nextInt}")
        try path.createDirectory() ==> path
        finally path.delete()
      }
    }
    'createDirectories - {
      'absolute - {
        val path = get(root, "tmp", s"foo${random.nextInt}", s"bar${random.nextInt}")
        try {
          path.getParent.deleteRecursive()
          path.getParent.delete()
          path.createDirectories() ==> path
          assert(path.exists)
        } finally path.getParent.deleteRecursive()
      }
      'relative - {
        val path = get(s"foo${random.nextInt}", s"bar${random.nextInt}")
        try {
          path.getParent.deleteRecursive()
          path.createDirectories().toAbsolutePath ==> path.toAbsolutePath
          assert(path.exists)
        } finally path.getParent.deleteRecursive()
      }
    }
    'isAbsolute - {
      'normal - {
        assert(get(root, "foo").isAbsolute)
        assert(!get("foo").isAbsolute)
      }
      'parent - {
        'absolute - {
          val base = get("", "foo")
          assert(!base.relativize(get("", "foo", "bar")).isAbsolute)
        }
        'relative - {
          val base = get("foo")
          assert(!base.relativize(get("foo", "bar")).isAbsolute)
        }
      }
    }
    'getParent - {
      'normal - {
        val path = get(root, "foo", "bar")
        path.getParent ==> get(root, "foo")
      }
      'root - {
        val path = get(root, "")
        path.getParent ==> null
      }
    }
  }
}
