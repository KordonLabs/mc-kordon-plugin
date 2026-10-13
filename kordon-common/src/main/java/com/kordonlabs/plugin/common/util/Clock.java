package com.kordonlabs.plugin.common.util;

/** Time source of the core, replaceable so that tests can run timers and backoff in virtual time. */
public interface Clock {

    /** Monotonic time for durations and event ages, with {@code System.nanoTime()} semantics. */
    long nanos();

    /** Wall-clock time in epoch milliseconds. */
    long millis();
}
