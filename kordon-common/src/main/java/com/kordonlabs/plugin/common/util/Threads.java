package com.kordonlabs.plugin.common.util;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Named threads of the core. Every thread is a daemon, so it never keeps the proxy alive, and logs uncaught throwables
 * to the plugin logger.
 */
public final class Threads {

    private Threads() {
    }

    /** One thread with exactly this name (for single-thread executors). */
    public static ThreadFactory named(String name, Logger logger) {
        return task -> newThread(task, name, logger);
    }

    /** Threads named {@code prefix-1}, {@code prefix-2} and so on. */
    public static ThreadFactory numbered(String prefix, Logger logger) {
        AtomicInteger next = new AtomicInteger();
        return task -> newThread(task, prefix + "-" + next.incrementAndGet(), logger);
    }

    /**
     * A one-thread scheduled executor named {@code name}: cancelled tasks leave the queue at once, delayed tasks are
     * dropped by {@code shutdown()}.
     */
    public static ScheduledThreadPoolExecutor scheduled(String name, Logger logger) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, named(name, logger));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    public static ExecutorService single(String name, Logger logger) {
        return Executors.newSingleThreadExecutor(named(name, logger));
    }

    /** A fixed pool of {@code threads} threads named {@code prefix-1..n}, created on demand. */
    public static ExecutorService fixed(String prefix, int threads, Logger logger) {
        return Executors.newFixedThreadPool(threads, numbered(prefix, logger));
    }

    /**
     * Calls {@code shutdownNow()} on every executor, then waits at most {@code await} for all of them together.
     * Null entries are skipped. Returns true when every executor terminated in time.
     */
    public static boolean shutdown(List<? extends ExecutorService> executors, Duration await) {
        for (ExecutorService executor : executors) {
            if (executor != null) executor.shutdownNow();
        }
        long deadline = System.nanoTime() + await.toNanos();
        boolean all = true;
        for (ExecutorService executor : executors) {
            if (executor == null) continue;
            long left = deadline - System.nanoTime();
            try {
                all &= executor.awaitTermination(Math.max(0, left), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return all;
    }

    /** Hands a throwable caught by a task wrapper to the current thread's uncaught-exception handler. */
    public static void report(Throwable t) {
        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
        if (handler != null) handler.uncaughtException(current, t);
    }

    private static Thread newThread(Runnable task, String name, Logger logger) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> logger.log(Level.SEVERE, "Unexpected error in " + t.getName(), e));
        return thread;
    }
}
