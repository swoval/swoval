package com.swoval.files

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ArrayBlockingQueue, ConcurrentHashMap, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import utest._
import utest.framework.{ HTree, Result }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{ Failure, Random, Success, Try }

object AllTests {
  val random = new Random()
  private implicit class StringOps(val s: String) extends AnyVal {
    def intValue(default: Int): Int = Try(Integer.valueOf(s).toInt).getOrElse(default)
  }
  def baseArgs(count: String, timeout: String): Int = {
    System.setProperty("swoval.test.timeout", timeout.intValue(default = 10).toString)
    count.intValue(default = 1)
  }
  def main(args: Array[String]): Unit = {
    val iterations = args match {
      case Array(count, timeout, debug) =>
        System.setProperty("swoval.debug", java.lang.Boolean.valueOf(debug).toString)
        baseArgs(count, timeout)
      case Array(count, timeout) => baseArgs(count, timeout)
    }
    try {
      1 to iterations foreach { i =>
        println(s"Iteration $i:")
        try {
          run(i)
        } catch {
          case e: Throwable =>
            System.err.println(s"Tests failed during run $i ($e)")
            e.printStackTrace(System.err)
            System.exit(1)
        }
      }
      println("finished")
      System.exit(0)
    } catch {
      case _: Exception => System.exit(1)
    }
  }
  def run(count: Int): Unit = {
    System.gc()
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
    val queue = new ArrayBlockingQueue[(String, Try[HTree[String, Result]])](tests.size)
    val failure = new AtomicReference[Option[Throwable]](None)
    def groupSize: Int = {
      val n = tests.size
      val uniform = random.nextInt(n)
      1 + (uniform * (uniform + 1)) / n
    }
    System.out.println(s"Group size: $groupSize")
    tests.grouped(1) foreach { group =>
      new Thread(s"${group.map(_._2)} test thread") {
        setDaemon(true)
        start()
        override final def run(): Unit =
          group.foreach {
            case (t, n) =>
              try queue.add(n -> Try(TestRunner.runAndPrint(t, n)))
              catch { case e: InterruptedException => queue.add(n -> Failure(e)) }
          }
      }
    }
    val completed = ConcurrentHashMap.newKeySet[String]
    tests.indices foreach { _ =>
      queue.poll(30, TimeUnit.SECONDS) match {
        case null if completed.size != tests.size =>
          throw new IllegalStateException(
            s"Test failed: ${tests.map(_._2).toSet diff completed.asScala.toSet} failed to complete")
        case (n, Success(result)) =>
          completed.add(n)
          result.leaves.map(_.value).foreach {
            case Failure(e) => failure.compareAndSet(None, Some(e))
            case _          =>
          }
        case (n, Failure(e)) =>
          completed.add(n)
          failure.compareAndSet(None, Some(e))
      }
    }
    failure.get.foreach(throw _)
  }
}
