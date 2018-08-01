package com.swoval.runtime;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides an api for adding shutdown hooks. This exists because there is no {@link
 * java.lang.Runtime#addShutdownHook} implementation available on scala.js. The hooks may be added
 * with a priority that controls the order in which the hooks run. Lower values run first.
 */
public class ShutdownHooks {
  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              @SuppressWarnings("EmptyCatchBlock")
              public void run() {
                shutdown.set(true);
                final List<Hook> hooksToRun = new ArrayList<>(hooks.values());
                Collections.sort(hooksToRun);
                for (final Hook hook : hooksToRun) {
                  try {
                    hook.runnable.run();
                  } catch (final NoClassDefFoundError e) {
                  }
                }
              }
            });
  }

  private static final Map<Integer, Hook> hooks = new LinkedHashMap<>();
  private static final AtomicInteger hookID = new AtomicInteger(0);
  private static final AtomicBoolean shutdown = new AtomicBoolean(false);
  private static final String pid =
      Platform.isWin() ? "" : ManagementFactory.getRuntimeMXBean().getName().replaceAll("@.*", "");
  private static final Object lock = new Object();

  static boolean isShutdown() {
    return shutdown.get();
  }

  static String getPid() {
    return pid;
  }

  private static class Hook implements Comparable<Hook> {
    private final int priority;
    private final Runnable runnable;

    private Hook(final int priority, final Runnable runnable) {
      this.priority = priority;
      this.runnable = runnable;
    }

    @Override
    public int compareTo(final Hook other) {
      return Integer.compare(this.priority, other.priority);
    }
  }

  /**
   * Add a removable hook to run at shutdown.
   *
   * @param priority controls the ordering of this hook. Lower values run first.
   * @param runnable the shutdown task to run
   * @return an id that can be used to later remove the runnable if it is no longer needed.
   */
  public static int addHook(final int priority, final Runnable runnable) {
    synchronized (lock) {
      final int id = hookID.getAndIncrement();
      hooks.put(id, new Hook(priority, runnable));
      return id;
    }
  }

  /**
   * Remove a shutdown hook that was added via {@link ShutdownHooks#addHook(int, Runnable)}.
   *
   * @param id the id returned by {@link ShutdownHooks#addHook(int, Runnable)}
   */
  public static void removeHook(final int id) {
    synchronized (lock) {
      hooks.remove(id);
    }
  }
}
