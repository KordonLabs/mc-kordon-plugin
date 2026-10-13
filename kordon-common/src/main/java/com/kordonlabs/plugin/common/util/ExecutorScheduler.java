package com.kordonlabs.plugin.common.util;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The production {@link Scheduler} over a {@link ScheduledExecutorService}. A task's throwable goes to the thread's
 * uncaught-exception handler, which logs it, instead of vanishing inside a {@code ScheduledFuture}.
 */
public final class ExecutorScheduler implements Scheduler {

    private static final Cancellable DROPPED = () -> false;

    private final ScheduledExecutorService executor;

    public ExecutorScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
        try {
            ScheduledFuture<?> future = executor.schedule(guarded(task), Math.max(0, delay), unit);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException stopping) {
            return DROPPED;
        }
    }

    @Override
    public void execute(Runnable task) {
        try {
            executor.execute(guarded(task));
        } catch (RejectedExecutionException stopping) {
            // The core is stopping; nothing may run any more.
        }
    }

    public ScheduledExecutorService executor() {
        return executor;
    }

    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                Threads.report(t);
            }
        };
    }
}
