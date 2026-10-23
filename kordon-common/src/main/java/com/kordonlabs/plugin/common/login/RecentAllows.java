package com.kordonlabs.plugin.common.login;

import com.kordonlabs.plugin.common.util.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Supports anti-bot proxies such as XCord that verify a player and then make them reconnect: an allowed panel verdict
 * for the same name, IP, UUID and client protocol is reused without a request for up to {@link #TTL_MILLIS} until it is consumed at
 * PostLogin. Blocked verdicts and verdicts the panel did not make are never stored, so a reconnect loop cannot extend
 * the window. A blacklist entry added in between is still enforced by the panel's join-time check. Thread-safe.
 */
public final class RecentAllows {

    public static final long TTL_MILLIS = 60_000;
    private static final long PRUNE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** Who a verdict was made for, and on which client version. */
    record Key(String nameLower, String ip, UUID uuid, int protocol) {

        static Key of(LoginAttempt a) {
            return new Key(a.name() == null ? null : a.name().toLowerCase(Locale.ROOT), a.ip(), a.uuid(),
                    a.protocol());
        }
    }

    private record Entry(LoginVerdict verdict, long atNanos) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private volatile long lastPruneNanos;

    public RecentAllows(Clock clock) {
        this.clock = clock;
        this.lastPruneNanos = clock.nanos();
    }

    /** Remembers an allowed panel verdict (ignored for any other). */
    public void remember(LoginAttempt attempt, LoginVerdict verdict) {
        if (attempt == null || verdict == null || !verdict.allow() || verdict.source() != LoginVerdict.Source.PANEL) {
            return;
        }
        long now = clock.nanos();
        entries.put(Key.of(attempt), new Entry(verdict, now));
        if (now - lastPruneNanos >= PRUNE_EVERY_NANOS) prune(now);
    }

    /** An unconsumed, unexpired allowed verdict for the same player, as a {@code REUSED} verdict with its loginId. */
    public Optional<LoginVerdict> find(LoginAttempt attempt) {
        if (attempt == null) return Optional.empty();
        Key key = Key.of(attempt);
        Entry e = entries.get(key);
        if (e == null) return Optional.empty();
        if (expired(e, clock.nanos())) {
            entries.remove(key, e);
            return Optional.empty();
        }
        LoginVerdict v = e.verdict();
        return Optional.of(new LoginVerdict(true, null, LoginVerdict.Source.REUSED, v.result(), v.decidedBy(),
                v.reasons(), v.wouldBlock(), v.loginId()));
    }

    /** Marks the entry of this player consumed (PostLogin happened). */
    public void consume(LoginAttempt attempt) {
        if (attempt != null) consume(Key.of(attempt));
    }

    void consume(Key key) {
        if (key != null) entries.remove(key);
    }

    /** Forgets every entry, used when the server key changes. */
    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private void prune(long now) {
        lastPruneNanos = now;
        entries.values().removeIf(e -> expired(e, now));
    }

    private static boolean expired(Entry e, long now) {
        return now - e.atNanos() >= TimeUnit.MILLISECONDS.toNanos(TTL_MILLIS);
    }
}
