package com.swoval.concurrent;

import java.util.concurrent.atomic.AtomicInteger;

public class ThreadFactory implements java.util.concurrent.ThreadFactory {
    private final ThreadGroup group;
    private final String name;
    private AtomicInteger counter = new AtomicInteger(0);
    public ThreadFactory(String name) {
        SecurityManager s = System.getSecurityManager();
        group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        this.name = name;
    }
    @Override
    public Thread newThread(Runnable r) {
        return new Thread(group, r, name + "-" + counter.incrementAndGet());
    }
}
