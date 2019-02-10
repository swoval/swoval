package com.swoval.files

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import utest._

import scala.util.{ Failure, Try }

object AllTests {
  def main(args: Array[String]): Unit = {
    val count = args.headOption.flatMap(a => Try(a.toInt).toOption).getOrElse(1)
    try {
      1 to count foreach { i =>
        println(s"Iteration $i:")
        try {
          run()
        } catch {
          case e: Throwable =>
            System.err.println(s"Tests failed during run $i")
            e.printStackTrace(System.err)
            System.exit(1)
        }
      }
      println("finished")
      System.exit(0)
    } catch {
      case e: Exception => System.exit(1)
    }
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
    val failure = new AtomicReference[Option[Throwable]](None)
    val threads = tests.map {
      case (t, n) =>
        val thread = new Thread(s"$n test thread") {
          setDaemon(true)
          override def run(): Unit = {
            val res = TestRunner.runAndPrint(t, n)
            res.leaves.toIndexedSeq.foreach { l =>
              l.value match {
                case Failure(e) => failure.compareAndSet(None, Some(e))
                case _          =>
              }
            }
            latch.countDown()
          }
        }
        thread.start()
        thread
    }
    latch.await(30, TimeUnit.SECONDS)
    println("joining threads")
    threads.foreach { t =>
      t.interrupt()
      t.join(5000)
    }
    failure.get.foreach(throw _)
  }
}
