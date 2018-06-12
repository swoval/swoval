package com.swoval.concurrent;

import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Provides a thread factory that names the threads with a given prefix */
public class ThreadFactory implements java.util.concurrent.ThreadFactory, AutoCloseable {
  private final ThreadGroup group;
  private final String name;
  private AtomicInteger counter = new AtomicInteger(0);
  private final WeakHashMap<Thread, Boolean> threads = new WeakHashMap<>();

  /**
   * Creates a new ThreadFactor with thread name prefix. The suffix will b
   *
   * @param name The prefix for the name of thread
   */
  public ThreadFactory(String name) {
    SecurityManager s = System.getSecurityManager();
    group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
    this.name = name;
  }

  @Override
  public Thread newThread(Runnable r) {
    final Thread thread = new Thread(group, r, name + "-" + counter.incrementAndGet());
    threads.put(thread, true);
    return thread;
  }

  public boolean created(final Thread thread) {
    Boolean result = threads.get(thread);
    return result != null && result;
  }

  @Override
  public void close() {
    threads.clear();
  }
}
