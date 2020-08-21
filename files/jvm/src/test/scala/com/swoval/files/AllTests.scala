package com
package swoval
package files

import java.io.{ OutputStream, PrintStream }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ArrayBlockingQueue, ConcurrentHashMap, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import utest._
import utest.framework.{ HTree, Result }

import scala.collection.JavaConverters._
import scala.util.{ Failure, Random, Success, Try }

object AllTests {
  swoval.test.setVerbose(false)
  val random = new Random()
  private implicit class StringOps(val s: String) extends AnyVal {
    def intValue(default: Int): Int = Try(Integer.valueOf(s).toInt).getOrElse(default)
  }
  final class CachingOutputStream extends OutputStream {
    val builder = new StringBuilder
    override def write(b: Int): Unit = builder.append(b.toChar)
    def printContent(outputStream: OutputStream): Unit = println(builder.toString)
  }
  def baseArgs(count: String, timeout: String, debug: Option[String]): Int = {
    debug.foreach(System.setProperty("swoval.test.debug", _))
    count.intValue(default = 1)
  }
  def main(args: Array[String]): Unit = {
    val iterations = args match {
      case Array(count, timeout, debug) => baseArgs(count, timeout, Some(debug))
      case Array(count, timeout)        => baseArgs(count, timeout, None)
    }
    try {
      1 to iterations foreach { i =>
        try run(i)
        catch {
          case e: Throwable =>
            System.err.println(s"Tests failed during run $i ($e)")
            e.printStackTrace(System.err)
            System.exit(1)
        }
      }
      System.exit(0)
    } catch {
      case _: Exception => System.exit(1)
    }
  }
  def run(count: Int): Unit = {
    System.gc()
    val now = System.nanoTime
    def test[T <: TestSuite](t: T): (Tests, String) =
      (t.tests, t.getClass.getCanonicalName)
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
      test(NioPathWatcherOverflowTest),
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
    print(s"Iteration $count (group size $groupSize)...")
    val outputStreams = new ConcurrentHashMap[String, CachingOutputStream]
    tests.grouped(groupSize) foreach { group =>
      new Thread(s"${group.map(_._2)} test thread") {
        setDaemon(true)
        start()
        override final def run(): Unit =
          group.foreach {
            case (t, n) =>
              val outputStream = new CachingOutputStream
              outputStreams.put(n, outputStream)
              val printStream = new PrintStream(outputStream, false)
              try queue.add(n -> Try(TestRunner.runAndPrint(t, n, printStream = printStream)))
              catch { case e: InterruptedException => queue.add(n -> Failure(e)) }
          }
      }
    }
    val completed = ConcurrentHashMap.newKeySet[String]
    tests.indices foreach { _ =>
      queue.poll(30, TimeUnit.SECONDS) match {
        case null if completed.size != tests.size =>
          tests.foreach {
            case (_, name) if !completed.contains(name) =>
              Option(outputStreams.get(name)).foreach(s => s.printContent(System.err))
          }
          throw new IllegalStateException(
            s"Test failed: ${tests.map(_._2).toSet diff completed.asScala.toSet} failed to complete"
          )
        case (n, Success(result)) =>
          completed.add(n)
          result.leaves.map(_.value).foreach {
            case Failure(e) =>
              System.err.println(s"Tests failed. Dumping output.")
              Option(outputStreams.get(n)).foreach(s => s.printContent(System.err))
              failure.compareAndSet(None, Some(e))
            case _ =>
          }
        case (n, Failure(e)) =>
          completed.add(n)
          System.err.println(s"Tests failed. Dumping output.")
          Option(outputStreams.get(n)).foreach(s => s.printContent(System.err))
          failure.compareAndSet(None, Some(e))
      }
    }
    failure.get.foreach(throw _)
    val elapsed = System.nanoTime - now
    println(s"done (${elapsed / 1.0e6} ms).")
  }
}
