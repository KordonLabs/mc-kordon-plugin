package com.kordonlabs.plugin.common.session;

import com.kordonlabs.plugin.common.api.dto.ClientSettings;
import com.kordonlabs.plugin.common.api.dto.SyncEvent;
import java.util.UUID;

/**
 * A queued session event, stamped with {@code Clock.nanos()} when it happened. The sync loop turns it into a wire
 * {@link SyncEvent} whose {@code ago} is measured at send time ({@link #toWire(long)}).
 */
public sealed interface SessionEvent permits SessionEvent.Join, SessionEvent.Info, SessionEvent.Quit {

    enum Kind { JOIN, INFO, QUIT }

    String sid();

    /** {@code Clock.nanos()} of the event. */
    long atNanos();

    Kind kind();

    /** The wire event, with {@code ago} computed at send time and clamped to {@link SyncEvent#MAX_AGO_MS}. */
    SyncEvent toWire(long nowNanos);

    /**
     * An upper bound of the event's JSON size (UTF-8, worst-case escaping of the free-text fields), so a batch never
     * has to be serialised under the tracker lock to respect the body limit.
     */
    int estimatedBytes();

    /** Milliseconds between {@code atNanos} and {@code nowNanos}, clamped to 0..{@link SyncEvent#MAX_AGO_MS}. */
    static long agoMillis(long atNanos, long nowNanos) {
        long ms = (nowNanos - atNanos) / 1_000_000L;
        return Math.max(0, Math.min(SyncEvent.MAX_AGO_MS, ms));
    }

    /** @param protocol the client's protocol number; 0 or less when unknown (sent as null) */
    record Join(String sid, long atNanos, UUID uuid, String name, String ip, Long loginId, int protocol,
                String backend, boolean bedrock, String brand, String locale, ClientSettings settings) implements SessionEvent {

        public Join(String sid, long atNanos, UUID uuid, String name, String ip, Long loginId, int protocol,
                    String backend, boolean bedrock, String brand, String locale) {
            this(sid, atNanos, uuid, name, ip, loginId, protocol, backend, bedrock, brand, locale, null);
        }

        @Override
        public Kind kind() {
            return Kind.JOIN;
        }

        @Override
        public SyncEvent toWire(long nowNanos) {
            return new SyncEvent.Join(sid, agoMillis(atNanos, nowNanos), uuid.toString(), name, ip, loginId,
                    protocol > 0 ? protocol : null, backend, bedrock, brand, locale, settings);
        }

        @Override
        public int estimatedBytes() {
            // keys, sid, uuid, numbers and booleans take about 230 bytes; free text x3 for UTF-8 and escaping
            return 240 + 3 * (len(name) + len(ip) + len(backend) + len(brand) + len(locale)) + settingsBytes(settings);
        }
    }

    /** Changed client details; null fields stay unchanged on the panel. */
    record Info(String sid, long atNanos, String brand, String locale, String backend, ClientSettings settings) implements SessionEvent {

        public Info(String sid, long atNanos, String brand, String locale, String backend) {
            this(sid, atNanos, brand, locale, backend, null);
        }

        @Override
        public Kind kind() {
            return Kind.INFO;
        }

        @Override
        public SyncEvent toWire(long nowNanos) {
            return new SyncEvent.Info(sid, agoMillis(atNanos, nowNanos), brand, locale, backend, settings);
        }

        @Override
        public int estimatedBytes() {
            return 120 + 3 * (len(brand) + len(locale) + len(backend)) + settingsBytes(settings);
        }
    }

    /** @param reason "quit", "kicked" or "gone" ({@code SyncEvent.Quit.REASON_*}) */
    record Quit(String sid, long atNanos, String reason) implements SessionEvent {

        @Override
        public Kind kind() {
            return Kind.QUIT;
        }

        @Override
        public SyncEvent toWire(long nowNanos) {
            return new SyncEvent.Quit(sid, agoMillis(atNanos, nowNanos), reason);
        }

        @Override
        public int estimatedBytes() {
            return 100;
        }
    }

    private static int settingsBytes(ClientSettings settings) {
        return settings == null ? 0 : 200;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
