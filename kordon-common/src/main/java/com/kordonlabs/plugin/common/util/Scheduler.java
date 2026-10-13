package com.kordonlabs.plugin.common.util;

import java.util.concurrent.TimeUnit;

/**
 * Scheduling abstraction of the core, replaceable so that tests run due tasks deterministically in virtual time.
 * Tasks submitted after the underlying executor was shut down are dropped silently.
 */
public interface Scheduler {

    /** Runs {@code task} once after {@code delay}. The returned handle cancels it if it has not started yet. */
    Cancellable schedule(Runnable task, long delay, TimeUnit unit);

    /** Runs {@code task} as soon as possible on the scheduler's thread, never on the caller's. */
    void execute(Runnable task);

    /** Handle of a scheduled task. */
    interface Cancellable {

        /** Cancels the task if it has not started; never interrupts a running one. True if it will not run. */
        boolean cancel();
    }
}
