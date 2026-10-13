package com.kordonlabs.plugin.common.login;

import com.kordonlabs.plugin.common.util.Clock;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The loginId of an allowed login, kept from the verdict until PostLogin and keyed by connection identity. Entries
 * expire after {@link #TTL_MILLIS} and are pruned on access. Thread-safe.
 *
 * <p>The connection is held weakly: a login that never reaches PostLogin (the client dropped during the hold, or
 * another plugin cancelled it afterwards, and the proxy fires no event for either) must not keep the proxy's whole
 * connection object alive. Under a login flood that would be tens of thousands of dead connections.
 */
public final class PendingLogins {

    public static final long TTL_MILLIS = 60_000;
    private static final long PRUNE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** Past this many entries a prune runs at most once a second instead of every 10 s. */
    static final int PRUNE_SIZE = 4_096;
    private static final long PRUNE_BUSY_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** A remembered allowed login. */
    record Entry(Long loginId, RecentAllows.Key key, long atNanos) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<Identity, Entry> entries = new ConcurrentHashMap<>();
    private volatile long lastPruneNanos;

    public PendingLogins(Clock clock) {
        this.clock = clock;
        this.lastPruneNanos = clock.nanos();
    }

    public void put(Object connectionKey, Long loginId) {
        put(connectionKey, loginId, null);
    }

    /** Remembers the loginId of this connection's allowed verdict and the player it was decided for. */
    void put(Object connectionKey, Long loginId, RecentAllows.Key key) {
        if (connectionKey == null) return;
        long now = clock.nanos();
        entries.put(new Identity(connectionKey), new Entry(loginId, key, now));
        maybePrune(now);
    }

    /** The loginId remembered for this connection, removed; null when none or expired. */
    public Long take(Object connectionKey) {
        Entry e = takeEntry(connectionKey);
        return e == null ? null : e.loginId();
    }

    /** The entry of this connection, removed; null when none or expired. */
    Entry takeEntry(Object connectionKey) {
        if (connectionKey == null) return null;
        long now = clock.nanos();
        Entry e = entries.remove(new Identity(connectionKey));
        maybePrune(now);
        return e == null || expired(e, now) ? null : e;
    }

    public void remove(Object connectionKey) {
        if (connectionKey == null) return;
        entries.remove(new Identity(connectionKey));
        maybePrune(clock.nanos());
    }

    /** Forgets every entry, used when the server key changes. */
    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private void maybePrune(long now) {
        long since = now - lastPruneNanos;
        if (since >= PRUNE_EVERY_NANOS || (entries.size() > PRUNE_SIZE && since >= PRUNE_BUSY_NANOS)) prune(now);
    }

    /** Drops expired entries and those whose connection object is gone. */
    void prune(long now) {
        lastPruneNanos = now;
        entries.entrySet().removeIf(e -> e.getKey().cleared() || expired(e.getValue(), now));
    }

    private static boolean expired(Entry e, long now) {
        return now - e.atNanos() >= TimeUnit.MILLISECONDS.toNanos(TTL_MILLIS);
    }

    /**
     * Identity of a connection object (a proxy's connection may override {@code equals}), held weakly. Two
     * identities are equal only while both still refer to the same live object (or they are the same identity).
     */
    static final class Identity {
        private final WeakReference<Object> ref;
        private final int hash;

        Identity(Object ref) {
            this.ref = new WeakReference<>(ref);
            this.hash = System.identityHashCode(ref);
        }

        boolean cleared() {
            return ref.get() == null;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof Identity other) || other.hash != hash) return false;
            Object mine = ref.get();
            return mine != null && mine == other.ref.get();
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
