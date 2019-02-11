package com.swoval.files

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ArrayBlockingQueue, ConcurrentHashMap, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import utest._
import utest.framework.{ HTree, Result }

import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }

object AllTests {
  def main(args: Array[String]): Unit = {
    val count = args.headOption.flatMap(a => Try(a.toInt).toOption).getOrElse(1)
    System.setProperty("swoval.test.timeout",
                       args.lastOption.flatMap(a => Try(a.toInt).toOption).getOrElse(10).toString)
    try {
      1 to count foreach { i =>
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
    tests.foreach {
      case (t, n) =>
        new Thread(s"$n test thread") {
          setDaemon(true)
          start()
          override def run(): Unit =
            try queue.add(n -> Try(TestRunner.runAndPrint(t, n)))
            catch { case e: InterruptedException => queue.add(n -> Failure(e)) }
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
