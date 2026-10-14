package com.kordonlabs.plugin.common.session;

import com.kordonlabs.plugin.common.api.dto.ClientSettings;
import com.kordonlabs.plugin.common.api.dto.SyncEvent;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The live sessions of this proxy and the producer side of the {@link EventQueue}.
 *
 * <p>Keyed by {@link OnlinePlayer#connectionKey()} in an identity map: a reconnect of the same UUID is a second,
 * independent session, and the old connection's late disconnect event closes only the old one. The tracker and the
 * queue share one lock ({@link EventQueue#lock()}), so {@link #takeBatch} takes the batch, the "queue fully drained"
 * decision and the open sids atomically.
 *
 * <p>Platform event threads only do O(1) work under the lock and call wrapper getters outside it. {@code onEnqueue}
 * is called after the lock is released. The info debounce and the Geyser brand check run on the worker.
 */
public final class SessionTracker {

    /** Client-detail changes within this window become one {@code info} with the last values. */
    static final Duration INFO_DEBOUNCE = Duration.ofSeconds(2);
    /** A Bedrock player without a brand this long after join is reported as "Geyser". */
    static final Duration GEYSER_DELAY = Duration.ofMillis(1500);
    static final String GEYSER_BRAND = "Geyser";
    /** Ghost sweep: tracked connections younger than this are never swept (the proxy may not list them yet). */
    static final Duration GHOST_MIN_AGE = Duration.ofSeconds(10);
    /**
     * Ghost sweep: a connection is closed as gone only once the proxy has not listed it for this long. Velocity
     * unregisters a player before it fires DisconnectEvent, and another plugin's async handler can hold that chain for
     * a while, so a quit on its way must still arrive as a quit or kick rather than be swept as gone.
     */
    static final Duration GHOST_GRACE = Duration.ofSeconds(10);
    /** A disconnect this soon after a Kordon kick is reported as "kicked". */
    static final Duration KICKED_WINDOW = Duration.ofSeconds(10);
    /** The panel skips a join with longer values, so they are cut here. */
    static final int MAX_BRAND = 64;
    static final int MAX_BACKEND = 64;
    static final int MAX_LOCALE = 16;

    private final Platform platform;
    private final EventQueue queue;
    private final Object lock;
    private final Clock clock;
    private final Scheduler worker;
    private final ThrottledLog log;
    private final Consumer<SessionEvent.Kind> onEnqueue;

    // Guarded by lock.
    private final Map<Object, TrackedSession> byKey = new IdentityHashMap<>();
    private final Map<String, TrackedSession> bySid = new HashMap<>();
    private final Map<UUID, Set<Object>> byUuid = new HashMap<>();
    /** The pending ghost re-check ({@link #GHOST_GRACE} after a sweep found a connection missing); null when none. */
    private Scheduler.Cancellable ghostRecheck;

    /**
     * @param onEnqueue called after every enqueue, outside the lock, with the event's kind
     */
    public SessionTracker(Platform platform, EventQueue queue, Clock clock, Scheduler worker, ThrottledLog log,
                          Consumer<SessionEvent.Kind> onEnqueue) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.lock = queue.lock();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.log = Objects.requireNonNull(log, "log");
        this.onEnqueue = onEnqueue == null ? kind -> { } : onEnqueue;
    }

    // ── platform events ──────────────────────────────────────────────────────────────────────────────────────────

    /** PostLogin: a new session (new sid) for this connection; enqueues a Join. A tracked connection is kept as is. */
    public void onJoin(OnlinePlayer p, Long loginId) {
        if (p == null) return;
        Object key = p.connectionKey();
        UUID uuid = p.uuid();
        String ip = p.ip();
        if (key == null || uuid == null || ip == null) return; // non-Inet connections are not tracked
        TrackedSession s = new TrackedSession(key, p, uuid, p.name(), ip, p.protocol(), p.bedrock(),
                UUID.randomUUID().toString(), clock.nanos(), loginId);
        s.backend = cleanBackend(p.backend());
        s.brand = cleanBrand(p.clientBrand());
        s.locale = cleanLocale(p.locale());
        s.settings = readSettings(p);
        synchronized (lock) {
            TrackedSession tracked = byKey.get(key);
            if (tracked != null) {
                // registerOnlineAtStart saw the connection before its PostLogin handler ran: keep that session.
                if (tracked.loginId == null) tracked.loginId = loginId;
                return;
            }
            index(s);
            queue.offer(s.joinEvent(s.joinNanos));
            if (s.bedrock && s.brand == null) {
                s.geyserCheck = worker.schedule(() -> geyserCheck(s), GEYSER_DELAY.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        onEnqueue.accept(SessionEvent.Kind.JOIN);
    }

    /** The connection switched to another backend server: an info after the debounce. */
    public void onBackend(Object connectionKey, String backend) {
        String cleaned = cleanBackend(backend);
        synchronized (lock) {
            TrackedSession s = byKey.get(connectionKey);
            if (s == null) return;
            if (cleaned != null) s.backend = cleaned;
            debounce(s);
        }
    }

    /** Re-reads brand and locale after the debounce; enqueues an Info only on change. */
    public void onClientInfoChanged(Object connectionKey) {
        synchronized (lock) {
            TrackedSession s = byKey.get(connectionKey);
            if (s != null) debounce(s);
        }
    }

    /** A client brand reported by the platform's brand listener; used when the wrapper has none. */
    public void onBrand(Object connectionKey, String brand) {
        String cleaned = cleanBrand(brand);
        if (cleaned == null) return;
        synchronized (lock) {
            TrackedSession s = byKey.get(connectionKey);
            if (s == null) return;
            s.brandHint = cleaned;
            debounce(s);
        }
    }

    /**
     * Disconnect: enqueues a Quit, "kicked" if {@link #markKicked} was called within {@link #KICKED_WINDOW}. A pending
     * info is flushed first, so a short session keeps its brand and locale.
     */
    public void onQuit(Object connectionKey) {
        TrackedSession s;
        boolean flushPending;
        synchronized (lock) {
            s = byKey.get(connectionKey);
            if (s == null) return;
            flushPending = s.infoFlush != null;
        }
        ClientInfo fresh = flushPending ? ClientInfo.read(s.player) : null;
        boolean info = false;
        synchronized (lock) {
            if (byKey.get(connectionKey) != s) return; // a concurrent quit or ghost sweep won
            long now = clock.nanos();
            if (fresh != null) info = queueInfoIfChanged(s, fresh, now);
            unindex(s);
            boolean kicked = s.kickedAtNanos != 0 && now - s.kickedAtNanos <= KICKED_WINDOW.toNanos();
            queue.offer(new SessionEvent.Quit(s.sid, now,
                    kicked ? SyncEvent.Quit.REASON_KICKED : SyncEvent.Quit.REASON_QUIT));
        }
        if (info) onEnqueue.accept(SessionEvent.Kind.INFO);
        onEnqueue.accept(SessionEvent.Kind.QUIT);
    }

    /**
     * Enqueues a Join without a loginId for everyone online, for example after a plugin reload. Called on the worker
     * once the platform's listeners are registered. Already tracked connections are skipped, so it is idempotent.
     *
     * <p>The list is a snapshot: a player who leaves while the loop runs may have the disconnect handled before the
     * join, when it was ignored. So every connection is checked again after its join, and a closed one gets its quit
     * now.
     */
    public void registerOnlineAtStart() {
        Collection<OnlinePlayer> online = platform.onlinePlayers();
        if (online == null) return;
        for (OnlinePlayer p : online) {
            try {
                onJoin(p, null);
                if (p != null && p.connectionKey() != null && !p.connected()) onQuit(p.connectionKey());
            } catch (RuntimeException e) {
                log.debug(() -> "Kordon: could not register an online player: " + e);
            }
        }
    }

    // ── kicks ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The stored wrapper of the session {@code sid}. */
    public Optional<OnlinePlayer> playerOf(String sid) {
        synchronized (lock) {
            TrackedSession s = bySid.get(sid);
            return s == null ? Optional.empty() : Optional.of(s.player);
        }
    }

    /** The quit reason becomes "kicked" if the disconnect follows within {@link #KICKED_WINDOW}. */
    public void markKicked(String sid) {
        synchronized (lock) {
            TrackedSession s = bySid.get(sid);
            if (s != null) s.kickedAtNanos = clock.nanos();
        }
    }

    /**
     * A kick order for a sid this proxy does not track: enqueues a "gone" quit so the panel closes the session. False,
     * with nothing queued, when the sid is tracked after all.
     */
    public boolean reportGone(String sid) {
        if (sid == null) return false;
        synchronized (lock) {
            if (bySid.containsKey(sid)) return false;
            queue.offer(new SessionEvent.Quit(sid, clock.nanos(), SyncEvent.Quit.REASON_GONE));
        }
        onEnqueue.accept(SessionEvent.Kind.QUIT);
        return true;
    }

    // ── the sync loop's side ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Takes up to {@code maxEvents} of the oldest events whose estimated size fits {@code maxBytes} (at least one) and
     * holds them as in flight, atomically with the queue.
     *
     * <p>The open sids are added only when {@code snapshotDue} and the batch drains the queue, since a quit still
     * queued must not make its session look lost. When the batch has events, both must also fit into
     * {@code maxBytes}; otherwise the snapshot follows alone in the next request.
     */
    public SyncBatch takeBatch(int maxEvents, int maxBytes, boolean snapshotDue) {
        synchronized (lock) {
            List<SessionEvent> head = queue.peek(Math.max(1, maxEvents));
            int budget = maxBytes - ENVELOPE_BYTES;
            int bytes = 0;
            int n = 0;
            for (SessionEvent e : head) {
                int b = e.estimatedBytes();
                if (n > 0 && bytes + b > budget) break;
                bytes += b;
                n++;
            }
            List<SessionEvent> events = List.copyOf(head.subList(0, n));
            List<String> sids = null;
            if (snapshotDue && n == queue.size() && (n == 0 || bytes + snapshotBytes(bySid.size()) <= budget)) {
                sids = List.copyOf(bySid.keySet());
                queue.snapshotTaken();
            }
            queue.hold(n);
            return new SyncBatch(events, sids, n);
        }
    }

    /** After a successful sync: removes the {@code n} events of the batch (see {@link EventQueue#commit}). */
    public void commit(int n) {
        queue.commit(n);
    }

    /** The request failed: its events stay queued. */
    public void release() {
        queue.release();
    }

    /** The panel switched sync off: queued events are dropped (sessions stay tracked for a later snapshot). */
    public void clearQueue() {
        queue.clear();
    }

    /**
     * Used when the server key changes: forgets every session, the queued events and all pending timers, and asks for
     * a snapshot. The old instance's sessions were closed by its final sync; the next {@link #registerOnlineAtStart}
     * gives everyone online a new sid.
     */
    public void clear() {
        synchronized (lock) {
            for (TrackedSession s : byKey.values()) s.cancelTimers();
            byKey.clear();
            bySid.clear();
            byUuid.clear();
            if (ghostRecheck != null) ghostRecheck.cancel();
            ghostRecheck = null;
            queue.clear();
            queue.requestSnapshot();
        }
    }

    /** Events were lost or rejected: the next batch that drains the queue carries a snapshot. */
    public void requestSnapshot() {
        queue.requestSnapshot();
    }

    public boolean snapshotRequested() {
        return queue.needsSnapshot();
    }

    /** Events dropped by queue overflow so far. */
    public long droppedEvents() {
        return queue.dropped();
    }

    /**
     * Closes as "gone" sessions older than {@link #GHOST_MIN_AGE} whose connection {@code online} has not listed for
     * {@link #GHOST_GRACE}. A connection seen missing for the first time is only marked and re-checked later, since
     * its quit may still be on its way; listed again, it is unmarked.
     */
    public int sweepGhosts(Collection<OnlinePlayer> online) {
        Set<Object> live = Collections.newSetFromMap(new IdentityHashMap<>());
        if (online != null) {
            for (OnlinePlayer p : online) {
                Object key = p == null ? null : p.connectionKey();
                if (key != null) live.add(key);
            }
        }
        List<String> swept = new ArrayList<>();
        synchronized (lock) {
            long now = clock.nanos();
            long minAge = GHOST_MIN_AGE.toNanos();
            long grace = GHOST_GRACE.toNanos();
            boolean waiting = false;
            for (TrackedSession s : List.copyOf(byKey.values())) {
                if (live.contains(s.key)) {
                    s.missing = false;
                    continue;
                }
                if (now - s.joinNanos <= minAge) continue;
                if (!s.missing) {
                    s.missing = true;
                    s.missingSinceNanos = now;
                }
                if (now - s.missingSinceNanos < grace) {
                    waiting = true;
                    continue;
                }
                unindex(s);
                queue.offer(new SessionEvent.Quit(s.sid, now, SyncEvent.Quit.REASON_GONE));
                swept.add(s.name);
            }
            if (waiting && ghostRecheck == null) {
                ghostRecheck = worker.schedule(this::recheckGhosts, GHOST_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (!swept.isEmpty()) {
            log.debug(() -> "Kordon: " + swept.size() + " session(s) no longer on the proxy, closed: " + swept);
            onEnqueue.accept(SessionEvent.Kind.QUIT);
        }
        return swept.size();
    }

    /** On the worker, {@link #GHOST_GRACE} after a sweep that left connections marked missing. */
    private void recheckGhosts() {
        synchronized (lock) {
            ghostRecheck = null;
        }
        Collection<OnlinePlayer> online;
        try {
            online = platform.onlinePlayers();
        } catch (RuntimeException e) {
            log.debug(() -> "Kordon: could not list the online players: " + e);
            return; // the next due snapshot sweeps again
        }
        if (online != null) sweepGhosts(online);
    }

    /**
     * Sids the panel does not know get their Join again (same sid and time, current details) unless it is still
     * queued. Sids the panel has closed get a new sid and a fresh Join. Sids no longer tracked are ignored, since their
     * quit is on its way.
     */
    public void resync(List<String> unknown, List<String> closed) {
        int joins = 0;
        synchronized (lock) {
            long now = clock.nanos();
            if (unknown != null && !unknown.isEmpty()) {
                Set<String> queuedJoins = queue.queuedJoinSids();
                for (String sid : unknown) {
                    TrackedSession s = sid == null ? null : bySid.get(sid);
                    if (s == null || queuedJoins.contains(sid)) continue;
                    queue.offer(s.joinEvent(s.joinNanos));
                    joins++;
                }
            }
            if (closed != null) {
                for (String sid : closed) {
                    TrackedSession s = sid == null ? null : bySid.get(sid);
                    if (s == null) continue;
                    bySid.remove(sid);
                    s.sid = UUID.randomUUID().toString();
                    s.joinNanos = now;
                    bySid.put(s.sid, s);
                    queue.offer(s.joinEvent(now));
                    joins++;
                }
            }
        }
        if (joins > 0) onEnqueue.accept(SessionEvent.Kind.JOIN);
    }

    /** Tracked sessions. */
    public int size() {
        synchronized (lock) {
            return byKey.size();
        }
    }

    /** Events waiting in the queue. */
    public int queued() {
        return queue.size();
    }

    /** The sids of the connections of {@code uuid}, one per connection. */
    List<String> sidsOf(UUID uuid) {
        synchronized (lock) {
            Set<Object> keys = byUuid.get(uuid);
            if (keys == null) return List.of();
            List<String> sids = new ArrayList<>();
            for (Object key : keys) sids.add(byKey.get(key).sid);
            return sids;
        }
    }

    /** The current sid of a tracked connection; null when not tracked. */
    String sidOf(Object connectionKey) {
        synchronized (lock) {
            TrackedSession s = byKey.get(connectionKey);
            return s == null ? null : s.sid;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Bytes reserved in every batch for the request envelope. */
    static final int ENVELOPE_BYTES = 256;

    static int snapshotBytes(int sids) {
        return 32 + 39 * sids; // a quoted UUID plus a comma per sid
    }

    private void index(TrackedSession s) {
        byKey.put(s.key, s);
        bySid.put(s.sid, s);
        byUuid.computeIfAbsent(s.uuid, u -> Collections.newSetFromMap(new IdentityHashMap<>())).add(s.key);
    }

    private void unindex(TrackedSession s) {
        byKey.remove(s.key);
        bySid.remove(s.sid);
        Set<Object> keys = byUuid.get(s.uuid);
        if (keys != null) {
            keys.remove(s.key);
            if (keys.isEmpty()) byUuid.remove(s.uuid);
        }
        s.cancelTimers();
    }

    /** Under the lock: starts the debounce window unless one is running; the flush reads the values at its end. */
    private void debounce(TrackedSession s) {
        if (s.infoFlush != null) return;
        s.infoFlush = worker.schedule(() -> flush(s), INFO_DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** On the worker: re-reads the client details and queues an info if anything changed. */
    private void flush(TrackedSession s) {
        synchronized (lock) {
            if (byKey.get(s.key) != s) return;
            s.infoFlush = null;
        }
        ClientInfo fresh = ClientInfo.read(s.player);
        boolean queued;
        synchronized (lock) {
            if (byKey.get(s.key) != s) return;
            queued = queueInfoIfChanged(s, fresh, clock.nanos());
        }
        if (queued) onEnqueue.accept(SessionEvent.Kind.INFO);
    }

    /** On the worker after a Bedrock join: reports "Geyser" when no brand is known yet. */
    private void geyserCheck(TrackedSession s) {
        synchronized (lock) {
            if (byKey.get(s.key) != s) return;
            s.geyserCheck = null;
        }
        ClientInfo fresh = ClientInfo.read(s.player);
        boolean queued;
        synchronized (lock) {
            if (byKey.get(s.key) != s) return;
            if (fresh.brand == null && s.brand == null && s.brandHint == null) s.brandHint = GEYSER_BRAND;
            queued = queueInfoIfChanged(s, fresh, clock.nanos());
        }
        if (queued) onEnqueue.accept(SessionEvent.Kind.INFO);
    }

    /**
     * Under the lock: merges fresh values and queues an info with every known value when one differs from the last
     * sent. The backend from {@link #onBackend} wins over the wrapper's, because the proxy updates the wrapper only
     * after its ServerConnectedEvent and a flush in between would report the previous server.
     */
    private boolean queueInfoIfChanged(TrackedSession s, ClientInfo fresh, long now) {
        String brand = fresh.brand != null ? fresh.brand : s.brandHint;
        if (brand != null) s.brand = brand;
        if (fresh.locale != null) s.locale = fresh.locale;
        if (s.backend == null && fresh.backend != null) s.backend = fresh.backend;
        if (fresh.settings != null) s.settings = fresh.settings;
        boolean settingsChanged = s.settings != null && !s.settings.equals(s.sentSettings);
        boolean changed = differs(s.brand, s.sentBrand) || differs(s.locale, s.sentLocale)
                || differs(s.backend, s.sentBackend) || settingsChanged;
        if (!changed) return false;
        s.sentBrand = s.brand;
        s.sentLocale = s.locale;
        s.sentBackend = s.backend;
        s.sentSettings = s.settings;
        queue.offer(new SessionEvent.Info(s.sid, now, s.brand, s.locale, s.backend, settingsChanged ? s.settings : null));
        return true;
    }

    /** A known value the panel has not been told yet; null never counts because the panel keeps the old value. */
    private static boolean differs(String current, String sent) {
        return current != null && !current.equals(sent);
    }

    static String cleanBrand(String brand) {
        return brand == null ? null : truncate(withoutControlChars(brand).strip(), MAX_BRAND);
    }

    static String cleanLocale(String locale) {
        if (locale == null) return null;
        String l = locale.strip().replace('_', '-');
        if (l.isEmpty() || l.length() > MAX_LOCALE || l.equalsIgnoreCase("und")) return null;
        return l;
    }

    /** Strips control characters like the panel does; they would also break the size estimate. */
    static String cleanBackend(String backend) {
        return backend == null ? null : truncate(withoutControlChars(backend).strip(), MAX_BACKEND);
    }

    private static String withoutControlChars(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String truncate(String s, int max) {
        if (s.isEmpty()) return null;
        if (s.length() <= max) return s;
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    /** The wrapper's current client details, read outside the lock because it calls proxy code. */
    static ClientSettings readSettings(OnlinePlayer p) {
        try {
            ClientSettings settings = p.clientSettings();
            if (settings == null) return null;
            return new ClientSettings(settings.viewDistance(), cleanToken(settings.chatMode()), settings.chatColors(),
                    cleanToken(settings.mainHand()), settings.skinParts(), settings.listing(), settings.filtering(),
                    cleanToken(settings.particles()));
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static String cleanToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 16) return null;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= 'a' && c <= 'z') && c != '_') return null;
        }
        return token;
    }

    private record ClientInfo(String brand, String locale, String backend, ClientSettings settings) {

        static ClientInfo read(OnlinePlayer p) {
            try {
                return new ClientInfo(cleanBrand(p.clientBrand()), cleanLocale(p.locale()), cleanBackend(p.backend()),
                        readSettings(p));
            } catch (RuntimeException e) {
                return new ClientInfo(null, null, null, null);
            }
        }
    }
}
