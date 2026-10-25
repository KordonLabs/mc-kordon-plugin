package com.kordonlabs.plugin.common.session;

import java.time.Duration;
import java.util.Arrays;

/**
 * Ping samples of one session: the median of the last valid samples, sent when it is first known and then only when
 * it changed noticeably, at most once a minute.
 */
final class PingSamples {

    static final Duration FIRST_SAMPLE = Duration.ofSeconds(10);
    static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);
    static final Duration MIN_SEND_INTERVAL = Duration.ofSeconds(60);
    static final int WINDOW = 5;
    static final int MIN_SAMPLES = 2;
    static final int MAX_PING_MS = 60_000;
    static final int MIN_CHANGE_MS = 15;

    static final int NONE = -1;

    private final int[] samples = new int[WINDOW];
    private int count;
    private int next;
    private int sentMs = NONE;
    private long sentAtNanos;

    /** Adds a sample; returns the median to send now, or {@link #NONE}. */
    int offer(int pingMs, long nowNanos) {
        if (pingMs > 0 && pingMs <= MAX_PING_MS) {
            samples[next] = pingMs;
            next = (next + 1) % WINDOW;
            if (count < WINDOW) count++;
        }
        if (count < MIN_SAMPLES) return NONE;
        int median = median();
        if (sentMs != NONE) {
            if (nowNanos - sentAtNanos < MIN_SEND_INTERVAL.toNanos()) return NONE;
            int diff = Math.abs(median - sentMs);
            if (diff < MIN_CHANGE_MS || diff * 4L < sentMs) return NONE;
        }
        sentMs = median;
        sentAtNanos = nowNanos;
        return median;
    }

    int median() {
        if (count == 0) return NONE;
        int[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int mid = count / 2;
        return count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid] + 1) / 2;
    }

    int count() {
        return count;
    }

    int sent() {
        return sentMs;
    }

    /** Sends the median again with the next sample. */
    void resend() {
        sentMs = NONE;
        sentAtNanos = 0;
    }
}
