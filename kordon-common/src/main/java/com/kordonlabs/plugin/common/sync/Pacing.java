package com.kordonlabs.plugin.common.sync;

import com.kordonlabs.plugin.common.session.SessionEvent;
import com.kordonlabs.plugin.common.state.ServerSettings.SyncPacing;

/**
 * When the next sync runs and when a snapshot is due. Pure arithmetic without scheduling; {@link SyncTask} guards it
 * with its lock.
 *
 * <p>After a successful run the next one follows at once while a backlog is left, soon when a snapshot is still due
 * or kicks were executed, and otherwise after the active or idle interval. An enqueue pulls the pending run closer
 * ({@link #nudgeDelayMillis}). A snapshot is due at the first run and then every {@code snapshotSec}.
 */
final class Pacing {

    static final long JOIN_NUDGE_MS = 1_000;
    static final long AFTER_KICKS_MS = 500;
    /** A due snapshot that did not fit next to the events goes alone this soon (not at once, as events may trickle). */
    static final long SNAPSHOT_RETRY_MS = 1_000;
    /** Queued events that make an enqueue schedule the next run at once. */
    static final int NUDGE_NOW_QUEUE = 200;

    private SyncPacing current = SyncPacing.DEFAULT;
    private boolean snapshotSent;
    private long lastSnapshotNanos;

    SyncPacing current() {
        return current;
    }

    void apply(SyncPacing pacing) {
        if (pacing != null) current = pacing;
    }

    /** The sync response's pacing; the heartbeat's {@code maxEvents} and {@code maxBodyBytes} are kept. */
    void apply(Integer intervalSec, Integer idleIntervalSec, Integer snapshotSec) {
        current = SyncPacing.of(current, intervalSec, idleIntervalSec, snapshotSec, null, null);
    }

    long intervalMillis() {
        return current.intervalSec() * 1000L;
    }

    long idleIntervalMillis() {
        return current.idleIntervalSec() * 1000L;
    }

    /** How soon an enqueue of {@code kind} wants a run, with {@code queued} events waiting. */
    long nudgeDelayMillis(SessionEvent.Kind kind, int queued) {
        if (queued >= NUDGE_NOW_QUEUE) return 0;
        return kind == SessionEvent.Kind.JOIN ? JOIN_NUDGE_MS : intervalMillis();
    }

    /**
     * The delay after a successful run.
     *
     * @param backlog         events queued before the batch was taken are still queued
     * @param snapshotPending a due snapshot was left out (it did not fit next to the events)
     * @param kicksExecuted   new kick orders were handed to the kick executor
     * @param active          players online or events queued
     * @param nudgeMillis     the earliest nudge received while the request was in flight; negative when none
     */
    long afterSuccessMillis(boolean backlog, boolean snapshotPending, boolean kicksExecuted, boolean active,
                            long nudgeMillis) {
        if (backlog) return 0;
        long delay = active ? intervalMillis() : idleIntervalMillis();
        if (snapshotPending) delay = Math.min(delay, SNAPSHOT_RETRY_MS);
        if (kicksExecuted) delay = Math.min(delay, AFTER_KICKS_MS);
        if (nudgeMillis >= 0) delay = Math.min(delay, nudgeMillis);
        return delay;
    }

    boolean snapshotDue(long nowNanos) {
        return !snapshotSent || nowNanos - lastSnapshotNanos >= current.snapshotSec() * 1_000_000_000L;
    }

    /** A snapshot taken at {@code takenAtNanos} was answered (or cannot be sent and waits a cycle). */
    void snapshotSent(long takenAtNanos) {
        snapshotSent = true;
        lastSnapshotNanos = takenAtNanos;
    }

    /** The next run carries a snapshot (sync switched back on). */
    void resetSnapshot() {
        snapshotSent = false;
    }
}
