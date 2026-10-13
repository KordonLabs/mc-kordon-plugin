package com.kordonlabs.plugin.common.heartbeat;

import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.ThrottledLog;

/**
 * Prints a "connection restored" console line after a lost link to the panel.
 *
 * <p>Every console warning about a lost link reports here ({@link #lost}). An outage starts only with a line that was
 * actually printed, since a throttled line is not a loss the admin saw. The next successful heartbeat or sync
 * ({@link #restored}) prints the restored line once per outage, unthrottled, with how long the link was down.
 * Console only. Thread-safe.
 */
public final class ConnectionWatch {

    private static final long MINUTE_NANOS = 60_000_000_000L;

    private final ThrottledLog log;
    private final Messages texts;
    private final Clock clock;
    private boolean down;
    private long sinceNanos;

    public ConnectionWatch(ThrottledLog log, Messages texts, Clock clock) {
        this.log = log;
        this.texts = texts;
        this.clock = clock;
    }

    /** A loss warning; {@code printed} tells whether it reached the console (an outage starts only then). */
    public synchronized void lost(boolean printed) {
        if (!printed || down) return;
        down = true;
        sinceNanos = clock.nanos();
    }

    /** A successful heartbeat or sync: prints the restored line once per outage. */
    public void restored() {
        long downNanos;
        synchronized (this) {
            if (!down) return;
            down = false;
            downNanos = clock.nanos() - sinceNanos;
        }
        log.info(downNanos < MINUTE_NANOS
                ? texts.console(MessageKey.LOG_CONNECTION_RESTORED_SHORT)
                : texts.console(MessageKey.LOG_CONNECTION_RESTORED, "min", minutes(downNanos)));
    }

    /** Forgets the current outage. */
    public synchronized void reset() {
        down = false;
    }

    /** A loss was announced and no answer came since. */
    public synchronized boolean down() {
        return down;
    }

    /** Whole minutes, rounded, at least one. */
    static long minutes(long nanos) {
        return Math.max(1, Math.round(nanos / 60e9));
    }
}
