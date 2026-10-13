package com.kordonlabs.plugin.common.util;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential retry delays for background calls: 5, 10, 20, 40, 80 and then 120 seconds at most, each with 20 percent
 * jitter. A server's {@code retry-after} is honoured as a lower bound. Not thread-safe: each instance is owned by one
 * loop on {@code Kordon-Worker}.
 */
public final class Backoff {

    public static final Duration BASE = Duration.ofSeconds(5);
    public static final Duration CAP = Duration.ofSeconds(120);
    public static final double JITTER = 0.2;

    private final long baseMillis;
    private final long capMillis;
    private final double jitter;
    private final DoubleSupplier random;
    private int failures;

    public Backoff() {
        this(BASE, CAP, JITTER, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random uniform values in [0, 1); tests pass a constant to get exact delays. */
    public Backoff(Duration base, Duration cap, double jitter, DoubleSupplier random) {
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
        this.jitter = jitter;
        this.random = random;
    }

    /** Registers a failure and returns the delay before the next attempt. */
    public Duration next() {
        return next(0);
    }

    /** Registers a failure; the delay is at least {@code retryAfterSec}, from a 429's {@code retry-after}. */
    public Duration next(int retryAfterSec) {
        long nominal = nominalMillis(failures);
        failures++;
        double factor = 1 - jitter + 2 * jitter * random.getAsDouble();
        long delay = Math.round(nominal * factor);
        return Duration.ofMillis(Math.max(delay, Math.max(0, retryAfterSec) * 1000L));
    }

    /** Back to the first delay, after a success. */
    public void reset() {
        failures = 0;
    }

    /** Consecutive failures since the last {@link #reset()}; 0 when not backing off. */
    public int failures() {
        return failures;
    }

    public boolean active() {
        return failures > 0;
    }

    /** The delay without jitter after {@code failures} previous failures: base * 2^failures, capped. */
    long nominalMillis(int failures) {
        long delay = baseMillis;
        for (int i = 0; i < failures && delay < capMillis; i++) delay *= 2;
        return Math.min(delay, capMillis);
    }
}
