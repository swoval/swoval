package com.swoval.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Vector;

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
                Collections.sort(hooks);
                for (final Hook hook : hooks) {
                  try {
                    hook.runnable.run();
                  } catch (final NoClassDefFoundError e) {
                  }
                }
              }
            });
  }

  private static final List<Hook> hooks = new Vector<>();

  private static class Hook implements Comparable<Hook> {
    private final int priority;
    private final Runnable runnable;

    public Hook(final int priority, final Runnable runnable) {
      this.priority = priority;
      this.runnable = runnable;
    }

    @Override
    public int compareTo(Hook other) {
      return Integer.compare(this.priority, other.priority);
    }
  }

  /**
   * Add a hook to run at shutdown.
   *
   * @param priority Controls the ordering of this hook. Lower values run first.
   * @param runnable The shutdown task to run
   */
  public static void addHook(final int priority, final Runnable runnable) {
    hooks.add(new Hook(priority, runnable));
  }
}
