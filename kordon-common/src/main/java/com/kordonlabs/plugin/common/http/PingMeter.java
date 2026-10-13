package com.kordonlabs.plugin.common.http;

import com.kordonlabs.plugin.common.util.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * Collects the response times of requests that got an HTTP answer of any status, reported as the heartbeat's
 * {@code pingMs}. Timeouts, network failures and aborted calls are not samples. Thread-safe.
 */
public final class PingMeter {

    static final int CAPACITY = 256;
    static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    /** Ring buffer: {@code count} samples, the newest at {@code (head - 1) mod CAPACITY}. */
    private final long[] atNanos = new long[CAPACITY];
    private final long[] millis = new long[CAPACITY];
    private int head;
    private int count;

    public PingMeter(Clock clock) {
        this.clock = clock;
    }

    /** Records one answered request that took {@code millis} ms; negative values count as 0. */
    public synchronized void record(long millis) {
        atNanos[head] = clock.nanos();
        this.millis[head] = Math.max(0, millis);
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    /**
     * The median in ms of the samples from the last {@link #WINDOW} (for an even count, the mean of the two middle
     * ones rounded half up); null when there are none.
     */
    public synchronized Integer medianMs() {
        long now = clock.nanos();
        long window = WINDOW.toNanos();
        long[] recent = new long[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int slot = Math.floorMod(head - 1 - i, CAPACITY);
            if (now - atNanos[slot] >= window) break; // samples are newest first, so the rest are older too
            recent[n++] = millis[slot];
        }
        if (n == 0) return null;
        Arrays.sort(recent, 0, n);
        long median = n % 2 == 1 ? recent[n / 2] : (recent[n / 2 - 1] + recent[n / 2] + 1) / 2;
        return (int) Math.min(Integer.MAX_VALUE, median);
    }
}
