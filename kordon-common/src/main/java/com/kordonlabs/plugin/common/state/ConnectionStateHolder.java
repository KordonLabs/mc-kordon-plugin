package com.kordonlabs.plugin.common.state;

import com.kordonlabs.plugin.common.util.Clock;

/**
 * The current {@link ConnectionState} and when the last heartbeat succeeded. Read on any thread, written by the
 * heartbeat and the core. Starts as {@link ConnectionState#UNREACHABLE}, meaning no answer yet.
 */
public final class ConnectionStateHolder {

    private final Clock clock;
    private volatile ConnectionState state = ConnectionState.UNREACHABLE;
    private volatile long lastOkAtMillis;

    public ConnectionStateHolder(Clock clock) {
        this.clock = clock;
    }

    public ConnectionState get() {
        return state;
    }

    public void set(ConnectionState state) {
        this.state = state;
    }

    /** A successful heartbeat: sets {@code state} and records now as the last answer. */
    public void answered(ConnectionState state) {
        this.lastOkAtMillis = clock.millis();
        this.state = state;
    }

    /** Wall-clock time of the last successful heartbeat; 0 when never. */
    public long lastOkAtMillis() {
        return lastOkAtMillis;
    }
}
