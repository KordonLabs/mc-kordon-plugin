package com.kordonlabs.plugin.common.session;

import com.kordonlabs.plugin.common.api.dto.ClientSettings;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.util.Scheduler;
import java.util.UUID;

/**
 * One tracked connection. Internal to {@link SessionTracker}; every mutable field is guarded by the tracker's lock.
 *
 * <p>The stored {@link OnlinePlayer} wrapper is the one the platform gave at join. Kicks disconnect exactly it, never
 * a fresh lookup by UUID, because a reconnect of the same account is another connection.
 */
public final class TrackedSession {

    final Object key;
    final OnlinePlayer player;
    final UUID uuid;
    final String name;
    final String ip;
    final int protocol;
    final boolean bedrock;

    /** Changes only when the panel reports the session closed and the player gets a new one. */
    String sid;
    long joinNanos;
    Long loginId;

    /** The latest known client details; re-sent joins carry them. */
    String backend;
    String brand;
    String locale;
    ClientSettings settings;
    /** A brand learnt outside the wrapper: from the platform's brand listener, or "Geyser" for Bedrock players. */
    String brandHint;

    /** What the last queued join or info told the panel; an info is queued only when something differs. */
    String sentBackend;
    String sentBrand;
    String sentLocale;
    ClientSettings sentSettings;

    /** {@code Clock.nanos()} of the last Kordon kick of this connection; 0 when never kicked. */
    long kickedAtNanos;
    /** The ghost sweep found the connection missing from the proxy's list (at {@link #missingSinceNanos}). */
    boolean missing;
    long missingSinceNanos;
    /** The pending debounced info flush; null when none. */
    Scheduler.Cancellable infoFlush;
    /** The pending Geyser-brand check of a Bedrock player; null when none. */
    Scheduler.Cancellable geyserCheck;

    TrackedSession(Object key, OnlinePlayer player, UUID uuid, String name, String ip, int protocol, boolean bedrock,
                   String sid, long joinNanos, Long loginId) {
        this.key = key;
        this.player = player;
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
        this.protocol = protocol;
        this.bedrock = bedrock;
        this.sid = sid;
        this.joinNanos = joinNanos;
        this.loginId = loginId;
    }

    /** The session id at the time of the call (the tracker's lock guards it). */
    public String sid() {
        return sid;
    }

    /** The wrapper stored at join. */
    public OnlinePlayer player() {
        return player;
    }

    /** A join with the current details, timed at {@code atNanos}. */
    SessionEvent.Join joinEvent(long atNanos) {
        sentBackend = backend;
        sentBrand = brand;
        sentLocale = locale;
        sentSettings = settings;
        return new SessionEvent.Join(sid, atNanos, uuid, name, ip, loginId, protocol, backend, bedrock, brand, locale,
                settings);
    }

    void cancelTimers() {
        if (infoFlush != null) infoFlush.cancel();
        if (geyserCheck != null) geyserCheck.cancel();
        infoFlush = null;
        geyserCheck = null;
    }
}
