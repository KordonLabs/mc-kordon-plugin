package com.kordonlabs.plugin.common.util;

/** The production {@link Clock}: {@code System.nanoTime()} and {@code System.currentTimeMillis()}. */
public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {
    }

    @Override
    public long nanos() {
        return System.nanoTime();
    }

    @Override
    public long millis() {
        return System.currentTimeMillis();
    }
}
