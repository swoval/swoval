package com.swoval.concurrent;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A reentrant lock with a lock() method that is always interruptible. If the thread is interrupted,
 * then the lock() method will return false.
 */
public class Lock {

  private final ReentrantLock reentrantLock = new ReentrantLock();

  /**
   * Locks this object
   *
   * @return true if the lock is acquired
   */
  public boolean lock() {
    boolean result = true;
    try {
      reentrantLock.lockInterruptibly();
    } catch (InterruptedException e) {
      result = false;
    }
    return result;
  }

  /** Unlocks this object. */
  public void unlock() {
    reentrantLock.unlock();
  }
}
