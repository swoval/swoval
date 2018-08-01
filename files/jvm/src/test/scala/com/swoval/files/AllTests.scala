package com.swoval.files

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import utest._

import scala.util.Try

object AllTests {
  def main(args: Array[String]): Unit = {
    val count = args.headOption.flatMap(a => Try(a.toInt).toOption).getOrElse(1)
    1 to count foreach { i =>
      println(s"Iteration $i:")
      run()
    }
    println("finished")
    System.exit(0)
  }
  def run(): Unit = {
    def test[T <: TestSuite](t: T): (Tests, String) =
      (t.tests, t.getClass.getName.replaceAll("[$]", ""))
    val tests = Seq(
      test(BasicFileCacheTest),
      test(NioBasicFileCacheTest),
      test(FileCacheSymlinkTest),
      test(NioFileCacheSymlinkTest),
      test(FileCacheOverflowTest),
      test(NioFileCacheOverflowTest),
      test(FileEventMonitorTest),
      test(DataViewTest),
      test(CachedFileTreeViewTest),
      test(PathTest),
      test(NioPathWatcherTest),
      test(DirectoryFileTreeViewTest),
      test(ApplePathWatcherTest)
    )
    val latch = new CountDownLatch(tests.size)
    val failed = new AtomicBoolean(false)
    val threads = tests.map {
      case (t, n) =>
        val thread = new Thread(s"$n test thread") {
          override def run(): Unit = {
            val res = TestRunner.runAndPrint(t, n)
            res.leaves.toIndexedSeq.foreach { l =>
              if (l.value.isFailure) {
                failed.set(true)
              }
            }
            latch.countDown()
          }
        }
        thread.start()
        thread
    }
    latch.await(30, TimeUnit.SECONDS)
    if (failed.get()) {
      throw new Exception("Tests failed")
    }
    println("joining threads")
    threads.foreach { t =>
      t.interrupt()
      t.join(5000)
    }
  }
}
