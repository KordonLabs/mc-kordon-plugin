package com.kordonlabs.plugin.common.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Bounded FIFO of {@link SessionEvent}s waiting for the panel. Memory only; a snapshot and resync heal a crash.
 *
 * <p>Events leave only through {@link #commit(int)} after a successful sync: the sync loop {@link #peek peeks} a
 * prefix, {@link #hold holds} it as in flight and commits it, or {@link #release releases} it after a failure.
 * Overflow drops the oldest {@code Info}, then the oldest event, but never one of the in-flight prefix, so a commit
 * always removes exactly the events that were sent. Overflow also requests a snapshot to heal what was lost.
 *
 * <p>Shares one lock with {@link SessionTracker} ({@link #lock()}), so the tracker can take the batch, the "queue
 * fully drained" decision and the open sids in one critical section. Every method takes the lock itself (monitors
 * are reentrant), so the queue is also safe on its own.
 */
public final class EventQueue {

    public static final int CAPACITY = 10_000;

    private final Object lock = new Object();
    private final int capacity;
    private final ArrayDeque<SessionEvent> events = new ArrayDeque<>();
    /** Info events in the queue; the overflow scan is skipped when there are none. */
    private int infos;
    /** Head events handed to the sync request in flight, never dropped by overflow. */
    private int inFlight;
    private boolean needsSnapshot;
    /** Events dropped by overflow since the queue was created. */
    private long dropped;

    public EventQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity " + capacity);
        this.capacity = capacity;
    }

    /** The one lock of the queue and its tracker. */
    Object lock() {
        return lock;
    }

    /**
     * Appends {@code event}. When full: drops the oldest {@code Info} outside the in-flight prefix, else the oldest
     * event outside it (else the new event itself) and sets {@code needsSnapshot}.
     *
     * @return true when an event was dropped
     */
    public boolean offer(SessionEvent event) {
        synchronized (lock) {
            if (events.size() < capacity) {
                add(event);
                return false;
            }
            needsSnapshot = true;
            dropped++;
            if (dropOldest(true) || dropOldest(false)) {
                add(event);
            }
            return true;
        }
    }

    /** Up to {@code max} oldest events, not removed. */
    public List<SessionEvent> peek(int max) {
        synchronized (lock) {
            int n = Math.min(Math.max(0, max), events.size());
            List<SessionEvent> out = new ArrayList<>(n);
            Iterator<SessionEvent> it = events.iterator();
            for (int i = 0; i < n; i++) out.add(it.next());
            return out;
        }
    }

    /** Marks the {@code n} oldest events as sent in the request in flight: overflow leaves them alone. */
    void hold(int n) {
        synchronized (lock) {
            inFlight = Math.max(0, Math.min(n, events.size()));
        }
    }

    /** The request in flight failed: its events stay queued and become droppable again. */
    void release() {
        synchronized (lock) {
            inFlight = 0;
        }
    }

    /**
     * Removes the {@code n} oldest events, the prefix a successful sync acknowledged. At most the held prefix is
     * removed, so a {@link #clear()} in between never costs newer events.
     */
    public void commit(int n) {
        synchronized (lock) {
            int remove = Math.min(Math.max(0, n), inFlight);
            for (int i = 0; i < remove; i++) {
                if (events.pollFirst() instanceof SessionEvent.Info) infos--;
            }
            inFlight = 0;
        }
    }

    /** Drops everything (the panel switched sync off). */
    public void clear() {
        synchronized (lock) {
            events.clear();
            infos = 0;
            inFlight = 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return events.size();
        }
    }

    /** Events were lost (overflow, a rejected batch): the next sync should carry a snapshot. */
    public boolean needsSnapshot() {
        synchronized (lock) {
            return needsSnapshot;
        }
    }

    public void requestSnapshot() {
        synchronized (lock) {
            needsSnapshot = true;
        }
    }

    /** A snapshot was taken into a batch (under the lock): the request is served. */
    void snapshotTaken() {
        synchronized (lock) {
            needsSnapshot = false;
        }
    }

    /** Events dropped by overflow so far. */
    public long dropped() {
        synchronized (lock) {
            return dropped;
        }
    }

    /** The sids of every queued {@code Join} (resync ignores {@code unknown} sids whose join is still queued). */
    Set<String> queuedJoinSids() {
        synchronized (lock) {
            Set<String> sids = new HashSet<>();
            for (SessionEvent e : events) {
                if (e instanceof SessionEvent.Join j) sids.add(j.sid());
            }
            return sids;
        }
    }

    private void add(SessionEvent event) {
        events.addLast(event);
        if (event instanceof SessionEvent.Info) infos++;
    }

    /** Drops the oldest event (an Info if {@code infoOnly}) behind the in-flight prefix; true if one was dropped. */
    private boolean dropOldest(boolean infoOnly) {
        if (infoOnly && infos == 0) return false;
        Iterator<SessionEvent> it = events.iterator();
        for (int i = 0; it.hasNext(); i++) {
            SessionEvent e = it.next();
            if (i < inFlight) continue;
            if (infoOnly && !(e instanceof SessionEvent.Info)) continue;
            it.remove();
            if (e instanceof SessionEvent.Info) infos--;
            return true;
        }
        return false;
    }
}
