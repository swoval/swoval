package com.swoval.files

import java.nio.file.Paths

import utest._

import scala.util.Random
import Path.{ separator => sep }
import com.swoval.test._

object PathTest extends TestSuite {
  val random = new Random()
  override val tests = Tests {
    'resolve - {
      'relative {
        'simple - {
          val parts = Seq("foo", "bar", "baz")
          val base = Path(parts: _*)
          val subdir = Path("buzz")
          val resolved = base.resolve(subdir)
          resolved.toString ==> (parts :+ "buzz").mkString(sep)
        }
        'nested - {
          val base = Path("", "foo")
          val relativeSubdir = base.relativize(Path("", "foo", "bar"))
          val relativeFile = relativeSubdir.relativize(Path("bar", "baz"))
          base.resolve(relativeSubdir.resolve(relativeFile)).toString ==>
            Seq("", "foo", "bar", "baz").mkString(sep)
        }
      }
      'absolute {
        'child - {
          val base = Path("", "foo", "bar", "baz")
          val subdir = Path(s"", "foo", "bar", "baz", "buzz")
          val resolved = base.resolve(subdir)
          resolved.toString ==> Seq("", "foo", "bar", "baz", "buzz").mkString(sep)
        }
        'unrelated - {
          val base = Path(s"", "foo", "bar", "baz")
          val subdir = Path(s"", "ok", "foo", "bar", "baz", "buzz")
          val resolved = base.resolve(subdir)
          resolved.toString ==> Seq("", "ok", "foo", "bar", "baz", "buzz").mkString(sep)
        }
      }
    }
    'relativize - {
      'child - {
        val base = Path("", "foo", "bar", "baz")
        val subdir = Path("", "foo", "bar", "baz", "buzz")
        base.relativize(subdir).toString ==> "buzz"
        base.resolve(subdir).toString ==> subdir.toString
      }
      'unrelated - {
        val base = Path("", "foo", "bar", "baz")
        val subdir = Path("", "ok", "foo", "bar", "baz", "buzz")
        base.relativize(subdir).toString ==> s"../../..$subdir"
      }
    }
    'parts - {
      'absolute - {
        val path = Path("", "foo", "bar", "baz")
        path.parts.map(_.toString) === Seq("foo", "bar", "baz")
      }
      'relative - {
        val parent = Path("", "foo", "bar", "baz", "")
        val child = Path("", "foo", "bar", "baz", "buzz")
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
    'mkdir - {
      'absolute - {
        val path = Paths.get(Platform.tmpDir).resolve(s"foo${random.nextInt}")
        path.delete()
        path.mkdir ==> path
        assert(path.exists)
      }
      'relative - {
        val path = Path(s"foo${random.nextInt}")
        try path.mkdir ==> path
        finally path.delete()
      }
    }
    'mkdirs - {
      'absolute - {
        val path = Path("", "tmp", s"foo${random.nextInt}", s"bar${random.nextInt}")
        try {
          path.getParent.deleteRecursive()
          path.getParent.delete()
          path.mkdirs() ==> path
          assert(path.exists)
        } finally path.getParent.deleteRecursive()
      }
      'relative - {
        val path = Path(s"foo${random.nextInt}", s"bar${random.nextInt}")
        try {
          path.getParent.deleteRecursive()
          path.mkdirs().toAbsolutePath ==> path.toAbsolutePath
          assert(path.exists)
        } finally path.getParent.deleteRecursive()
      }
    }
    'isAbsolute - {
      'normal - {
        assert(Path("", "foo").isAbsolute)
        assert(!Path("foo").isAbsolute)
      }
      'parent - {
        'absolute - {
          val base = Path("", "foo")
          assert(!base.relativize(Path("", "foo", "bar")).isAbsolute)
        }
        'relative - {
          val base = Path("foo")
          assert(!base.relativize(Path("foo", "bar")).isAbsolute)
        }
      }
    }
  }
}
