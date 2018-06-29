package com.swoval.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Vector;

public class ShutdownHooks {
  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                Collections.sort(hooks);
                for (final Hook hook : hooks) {
                  hook.runnable.run();
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

  public static void addHook(final int priority, final Runnable runnable) {
    hooks.add(new Hook(priority, runnable));
  }
}
