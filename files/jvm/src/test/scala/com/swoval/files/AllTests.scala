package com
package swoval
package files

import java.io.{ OutputStream, PrintStream }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ArrayBlockingQueue, ConcurrentHashMap, TimeUnit }

import com.swoval.files.apple.FileEventMonitorTest
import com.swoval.files.test.LoggingTestSuite
import utest._
import utest.framework.{ HTree, Result }

import scala.collection.JavaConverters._
import scala.util.{ Failure, Random, Success, Try }

object AllTests {
  swoval.test.setVerbose(false)
  private[this] val outputStreams = new ConcurrentHashMap[String, CachingOutputStream].asScala
  LoggingTestSuite.setOutputStreamFactory((s: String) => {
    outputStreams.synchronized {
      outputStreams.get(s) match {
        case Some(os) => os
        case None =>
          val outputStream = new CachingOutputStream
          outputStreams.put(s, outputStream)
          outputStream
      }
    }
  })
  val random = new Random()
  private implicit class StringOps(val s: String) extends AnyVal {
    def intValue(default: Int): Int = Try(Integer.valueOf(s).toInt).getOrElse(default)
  }
  final class CachingOutputStream extends OutputStream {
    val size = 20000
    val limit = 10
    var builders: Seq[StringBuilder] = new StringBuilder(size) :: Nil
    override def write(b: Int): Unit = {
      val builder = builders.last
      if (builder.size >= size - limit) {
        val newBuilder = new StringBuilder(size)
        builders = builders :+ newBuilder
        newBuilder.append(b.toChar)
      } else {
        builder.append(b.toChar)
      }
    }
    def printContent(outputStream: OutputStream): Unit = {
      builders.foreach { b =>
        outputStream.write(b.toString.getBytes)
      }
      outputStream.flush()
    }
  }
  def baseArgs(count: String,
               timeout: String,
               debug: Option[String],
               logger: Option[String]): Int = {
    debug.foreach(System.setProperty("swoval.debug", _))
    logger.foreach(System.setProperty("swoval.debug.logger", _))
    count.intValue(default = 1)
  }
  def main(args: Array[String]): Unit = {
    val iterations = args match {
      case Array(count, timeout, debug, logger) =>
        baseArgs(count, timeout, Some(debug), Some(logger))
      case Array(count, timeout, debug) => baseArgs(count, timeout, Some(debug), None)
      case Array(count, timeout)        => baseArgs(count, timeout, None, None)
    }
    try {
      1 to iterations foreach { i =>
        outputStreams.clear()
        try {
          run(i)
        } catch {
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
    def test[T <: LoggingTestSuite](t: T): (Tests, String, T) =
      (t.tests, t.getClass.getCanonicalName, t)
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
    print(s"Iteration $count (group size $groupSize)...")
    tests.grouped(groupSize) foreach { group =>
      new Thread(s"${group.map(_._2)} test thread") {
        setDaemon(true)
        start()
        override final def run(): Unit =
          group.foreach {
            case (t, n, test) =>
              val outputStream = new CachingOutputStream
              outputStreams.put(n, outputStream)
              test.setOutputStream(outputStream)
              test.setName(n)
              if (System.getProperty("swoval.alltest.verbose", "false") == "true") {
                test.register()
              }
              val printStream = new PrintStream(outputStream, false)
              try queue.add(n -> Try(TestRunner.runAndPrint(t, n, printStream = printStream)))
              catch { case e: InterruptedException => queue.add(n -> Failure(e)) } finally test
                .unregister()
          }
      }
    }
    val completed = ConcurrentHashMap.newKeySet[String]
    tests.indices foreach { _ =>
      queue.poll(30, TimeUnit.SECONDS) match {
        case null if completed.size != tests.size =>
          tests.foreach {
            case (_, name, _) if !completed.contains(name) =>
              outputStreams.get(name).foreach(s => s.printContent(System.err))
          }
          throw new IllegalStateException(
            s"Test failed: ${tests.map(_._2).toSet diff completed.asScala.toSet} failed to complete")
        case (n, Success(result)) =>
          completed.add(n)
          result.leaves.map(_.value).foreach {
            case Failure(e) =>
              System.err.println(s"Tests failed. Dumping output.")
              outputStreams.get(n).foreach(s => s.printContent(System.err))
              failure.compareAndSet(None, Some(e))
            case _ =>
          }
        case (n, Failure(e)) =>
          completed.add(n)
          System.err.println(s"Tests failed. Dumping output.")
          outputStreams.get(n).foreach(s => s.printContent(System.err))
          failure.compareAndSet(None, Some(e))
      }
    }
    failure.get.foreach(throw _)
    println("done.")
  }
}
