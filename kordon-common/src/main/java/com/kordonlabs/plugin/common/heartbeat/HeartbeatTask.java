package com.kordonlabs.plugin.common.heartbeat;

import com.kordonlabs.plugin.common.KordonInfo;
import com.kordonlabs.plugin.common.api.ApiResult;
import com.kordonlabs.plugin.common.api.KordonApi;
import com.kordonlabs.plugin.common.api.dto.HeartbeatRequest;
import com.kordonlabs.plugin.common.api.dto.HeartbeatResponse;
import com.kordonlabs.plugin.common.http.PingMeter;
import com.kordonlabs.plugin.common.install.ApiBase;
import com.kordonlabs.plugin.common.install.Credentials;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.state.ConnectionState;
import com.kordonlabs.plugin.common.state.ConnectionStateHolder;
import com.kordonlabs.plugin.common.state.ServerSettings;
import com.kordonlabs.plugin.common.state.ServerSettings.SyncPacing;
import com.kordonlabs.plugin.common.state.SettingsHolder;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.ProxyIdentity;
import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.SemVer;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The heartbeat loop, running on the worker thread. The first heartbeat goes out right after {@link #start()}, then
 * one every {@code heartbeatIntervalSec}, plus on demand via {@link #requestNow()} (changed settings version in a sync
 * answer, a sync 401, {@code /kordon reload}). Single-flight, and heartbeats are at least one second apart. Nothing is
 * sent while the plugin is not configured. Credentials are read per run.
 *
 * <p>A 200 answer replaces the in-memory {@link ServerSettings} and logs connection, settings and feature changes.
 * 401, 404, non-JSON and redirect answers mark the panel unreachable or the key refused and log a throttled warning
 * that starts a {@link ConnectionWatch} outage. A disabled account blocks {@code /login} until the next 200. 429 waits
 * for {@code Retry-After}; network errors, timeouts and 5xx keep the state and retry on the next tick. A run never
 * throws.
 */
public final class HeartbeatTask {

    /** Heartbeats are never closer than this. */
    static final Duration MIN_SPACING = Duration.ofSeconds(1);
    static final Duration UPDATE_NOTICE_EVERY = Duration.ofHours(24);
    /** Throttle keys, equal to the message keys; {@code log.panel_redirect} is shared with the sync loop. */
    public static final String LOG_UNAUTHORIZED = "log.unauthorized";
    public static final String LOG_BAD_PANEL_URL = "log.bad_panel_url";
    public static final String LOG_PANEL_REDIRECT = "log.panel_redirect";

    private final Platform platform;
    private final KordonApi api;
    /** The Kordon API address and the key in use (read per run). */
    private final Supplier<Credentials> credentials;
    /** The embedded {@code serverId}, used for the plugin page link before the first 200; may be null. */
    private final String installServerId;
    private final SettingsHolder settings;
    private final ConnectionStateHolder state;
    /** May be null. */
    private final ConnectionWatch watch;
    private final Messages messages;
    private final ThrottledLog log;
    private final Scheduler worker;
    private final Clock clock;
    private final String instance;
    private final PingMeter ping;
    private final ProxyIdentity identity;
    private final Consumer<ServerSettings> onSuccess;

    // Scheduling (guarded by lock).
    private final Object lock = new Object();
    private boolean started;
    private boolean stopped;
    private boolean inFlight;
    private boolean again;
    private Scheduler.Cancellable next;
    private long nextDueNanos;
    private long lastSentNanos;
    private boolean sentOnce;

    // Answer handling (worker thread only).
    private ServerSettings lastApplied;
    private boolean connectedLogged;
    private boolean legacyLogged;
    private boolean disabledLogged;
    private long lastUpdateNoticeNanos;
    private boolean updateNoticed;

    /**
     * @param credentials     the Kordon API address and key in use, read per run (nothing is sent when not configured)
     * @param installServerId the embedded {@code serverId}; null when unknown
     * @param watch     reports restored connections; may be null
     * @param instance  this instance UUID
     * @param ping      response times of requests to Kordon, reported as {@code pingMs}; may be null
     * @param identity  source of {@code host} and {@code node} (blocks on first use, so worker only); may be null
     * @param onSuccess called on the worker after every successful heartbeat with the new settings
     */
    public HeartbeatTask(Platform platform, KordonApi api, Supplier<Credentials> credentials, String installServerId,
                         SettingsHolder settings, ConnectionStateHolder state, ConnectionWatch watch,
                         Messages messages, ThrottledLog log, Scheduler worker, Clock clock, String instance,
                         PingMeter ping, ProxyIdentity identity, Consumer<ServerSettings> onSuccess) {
        this.platform = platform;
        this.api = api;
        this.credentials = credentials == null ? () -> Credentials.NONE : credentials;
        this.installServerId = installServerId;
        this.settings = settings;
        this.state = state;
        this.watch = watch;
        this.messages = messages;
        this.log = log;
        this.worker = worker;
        this.clock = clock;
        this.instance = instance;
        this.ping = ping;
        this.identity = identity;
        this.onSuccess = onSuccess;
    }

    /**
     * The current heartbeat body of this proxy. Blocks on the identity's first use, so call it on the worker only.
     * {@code node} lets the panel recognise a restart of the same plugin copy.
     */
    public static HeartbeatRequest request(Platform platform, String instance, ProxyIdentity identity, PingMeter ping) {
        return new HeartbeatRequest(platform.platformId(), platform.serverVersion(), KordonInfo.version(),
                platform.onlineMode(), platform.onlineCount(), instance, KordonInfo.FEATURES,
                identity == null ? null : identity.node(), identity == null ? null : identity.host(),
                ping == null ? null : ping.medianMs());
    }

    /** Schedules the first heartbeat now (async) and the periodic ones. Called once. */
    public void start() {
        start(Duration.ZERO);
    }

    /** Schedules the first heartbeat in {@code firstDelay} and the periodic ones. */
    public void start(Duration firstDelay) {
        synchronized (lock) {
            if (started || stopped) return;
            started = true;
            scheduleLocked(firstDelay == null || firstDelay.isNegative() ? 0 : firstDelay.toNanos(), true);
        }
    }

    /**
     * Used by {@code /kordon link} on the worker: applies the successful answer of the validation heartbeat as if this
     * task had sent it, and counts it as sent for spacing. Ignored once stopped.
     */
    public void prime(HeartbeatResponse body) {
        if (body == null) return;
        synchronized (lock) {
            if (stopped) return;
            lastSentNanos = clock.nanos();
            sentOnce = true;
        }
        try {
            ok(body);
        } catch (Throwable t) {
            log.debug(() -> "heartbeat answer not handled: " + t);
        }
    }

    /** A heartbeat as soon as possible, coalesced with a pending or running one. Any thread. */
    public void requestNow() {
        synchronized (lock) {
            if (!started || stopped) return;
            if (inFlight) {
                again = true;
                return;
            }
            scheduleLocked(0, true);
        }
    }

    /** Cancels the pending run; no new runs are scheduled and an in-flight answer is ignored. Any thread. */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            if (next != null) next.cancel();
            next = null;
        }
    }

    // ── scheduling ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Schedules the next run in {@code delayNanos} (never earlier than {@link #MIN_SPACING} after the last request).
     * {@code keepEarlier}: an already pending run that is due at or before that time stays (coalescing).
     */
    private void scheduleLocked(long delayNanos, boolean keepEarlier) {
        if (stopped) return;
        long now = clock.nanos();
        long delay = Math.max(0, delayNanos);
        if (sentOnce) delay = Math.max(delay, lastSentNanos + MIN_SPACING.toNanos() - now);
        long due = now + delay;
        if (next != null) {
            if (keepEarlier && nextDueNanos - due <= 0) return;
            next.cancel();
        }
        nextDueNanos = due;
        next = worker.schedule(this::run, delay, TimeUnit.NANOSECONDS);
    }

    /**
     * Ends a run and schedules the next one in the same critical section, so a {@link #requestNow()} from another
     * thread is never overwritten: while {@code inFlight} it only sets {@code again}, which wins over {@code nextIn}.
     */
    private void finishRun(Duration nextIn) {
        synchronized (lock) {
            inFlight = false;
            boolean runAgain = again;
            again = false;
            scheduleLocked((runAgain ? Duration.ZERO : nextIn).toNanos(), false);
        }
    }

    private Duration interval() {
        return Duration.ofSeconds(settings.get().heartbeatIntervalSec());
    }

    // ── one run ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void run() {
        synchronized (lock) {
            next = null;
            if (stopped || inFlight) return;
            inFlight = true;
        }
        Credentials used = credentials.get();
        if (state.get() == ConnectionState.NOT_CONFIGURED || used == null || !used.configured()) {
            finishRun(interval()); // not configured yet: nothing to send
            return;
        }
        CompletableFuture<ApiResult<HeartbeatResponse>> call;
        try {
            HeartbeatRequest request = request(platform, instance, identity, ping);
            synchronized (lock) {
                lastSentNanos = clock.nanos();
                sentOnce = true;
            }
            call = api.heartbeat(request);
        } catch (Throwable t) {
            log.debug(() -> "heartbeat not sent: " + t);
            call = CompletableFuture.completedFuture(ApiResult.noAnswer(ApiResult.Outcome.NETWORK));
        }
        call.whenComplete((result, error) -> worker.execute(() -> answered(used, result, error)));
    }

    private void answered(Credentials used, ApiResult<HeartbeatResponse> result, Throwable error) {
        synchronized (lock) {
            if (stopped) {
                inFlight = false;
                return;
            }
        }
        Duration nextIn;
        try {
            ApiResult<HeartbeatResponse> r = error != null || result == null
                    ? ApiResult.noAnswer(ApiResult.Outcome.NETWORK) : result;
            nextIn = handle(used, r);
        } catch (Throwable t) {
            log.debug(() -> "heartbeat answer not handled: " + t);
            nextIn = interval();
        }
        finishRun(nextIn);
    }

    /** Applies one answer; returns the delay until the next heartbeat. */
    private Duration handle(Credentials used, ApiResult<HeartbeatResponse> r) {
        switch (r.outcome()) {
            case OK -> {
                return Duration.ofSeconds(ok(r.body()).heartbeatIntervalSec());
            }
            case UNAUTHORIZED -> {
                setState(ConnectionState.UNAUTHORIZED);
                ServerSettings s = settings.get();
                lost(log.warn(LOG_UNAUTHORIZED, messages.console(MessageKey.LOG_UNAUTHORIZED,
                        "tokenPrefix", used.tokenPrefix(),
                        "pluginPage", pluginPage(),
                        "failOpenAction", messages.english().get(MessageKey.failOpenAction(s.failOpen())))));
                return interval();
            }
            case ACCOUNT_DISABLED -> {
                state.set(ConnectionState.DISABLED);
                if (!disabledLogged) {
                    disabledLogged = true;
                    log.warn(messages.console(MessageKey.LOG_ACCOUNT_DISABLED));
                }
                return interval();
            }
            case UNSUPPORTED -> {
                setState(ConnectionState.UNREACHABLE);
                lost(log.warn(LOG_BAD_PANEL_URL, messages.console(MessageKey.LOG_BAD_PANEL_URL,
                        "apiUrl", used.apiUrl(), "status", r.status(), "panelUrl", ApiBase.pagesUrl(used.apiUrl()))));
                return interval();
            }
            case REDIRECT -> {
                setState(ConnectionState.UNREACHABLE);
                String location = r.redirectOrigin() == null ? "?" : r.redirectOrigin();
                lost(log.warn(LOG_PANEL_REDIRECT, messages.console(MessageKey.LOG_PANEL_REDIRECT,
                        "apiUrl", used.apiUrl(), "location", location, "panelUrl", ApiBase.pagesUrl(used.apiUrl()))));
                return interval();
            }
            case RATE_LIMITED -> {
                return Duration.ofSeconds(Math.max(1, r.retryAfterSec()));
            }
            default -> {
                log.debug(() -> "heartbeat failed: " + r.outcome() + (r.status() > 0 ? " " + r.status() : ""));
                return interval();
            }
        }
    }

    /** A loss warning was emitted; {@code printed} is false when it was throttled away. */
    private void lost(boolean printed) {
        if (watch != null) watch.lost(printed);
    }

    /** Applies a successful answer and returns the new settings. */
    private ServerSettings ok(HeartbeatResponse body) {
        ServerSettings previous = settings.get();
        ServerSettings next = settingsOf(body, previous);
        settings.set(next);
        ConnectionState before = state.get();
        state.answered(next.legacyPanel() ? ConnectionState.LEGACY_PANEL : ConnectionState.OK);
        if (before != ConnectionState.OK && before != ConnectionState.LEGACY_PANEL) {
            // A problem that comes back later is reported at once again.
            log.reset(LOG_UNAUTHORIZED);
            log.reset(LOG_BAD_PANEL_URL);
            log.reset(LOG_PANEL_REDIRECT);
        }
        disabledLogged = false;
        if (watch != null) watch.restored();

        // Console lines are English, so their labels are too.
        String mode = messages.english().get(MessageKey.mode(next.mode()));
        String failOpen = messages.english().get(MessageKey.failOpen(next.failOpen()));
        if (!connectedLogged) {
            connectedLogged = true;
            String server = next.serverName() == null ? "" : next.serverName();
            log.info(messages.console(MessageKey.LOG_CONNECTED, "server", server, "mode", mode, "failOpen", failOpen));
        } else if (lastApplied != null && (!next.mode().equals(lastApplied.mode())
                || next.failOpen() != lastApplied.failOpen())) {
            log.info(messages.console(MessageKey.LOG_SETTINGS_CHANGED, "mode", mode, "failOpen", failOpen));
        }
        features(next);
        updateNotice(next);
        lastApplied = next;

        try {
            onSuccess.accept(next);
        } catch (Throwable t) {
            log.debug(() -> "heartbeat listener failed: " + t);
        }
        return next;
    }

    private void features(ServerSettings next) {
        if (next.legacyPanel()) {
            if (!legacyLogged) {
                legacyLogged = true;
                log.warn(messages.console(MessageKey.LOG_LEGACY_PANEL));
            }
            return;
        }
        legacyLogged = false;
        ServerSettings last = lastApplied;
        if (last == null || last.legacyPanel()) return;
        // A vanished sync feature is logged by SyncTask.onHeartbeat, which also clears its queue.
        if (last.lists() != null && next.lists() == null) featureOff(MessageKey.FEATURE_LISTS);
        if (last.lookup() != null && next.lookup() == null) featureOff(MessageKey.FEATURE_LOOKUP);
    }

    private void featureOff(MessageKey feature) {
        log.warn(messages.console(MessageKey.LOG_FEATURE_OFF, "feature", messages.english().get(feature)));
    }

    /** Logs an update notice at most once per 24 hours while the panel offers a newer version. */
    private void updateNotice(ServerSettings next) {
        String latest = next.latestVersion();
        if (latest == null || !SemVer.isOlder(KordonInfo.version(), latest)) return;
        long now = clock.nanos();
        if (updateNoticed && now - lastUpdateNoticeNanos < UPDATE_NOTICE_EVERY.toNanos()) return;
        updateNoticed = true;
        lastUpdateNoticeNanos = now;
        log.info(messages.console(MessageKey.LOG_UPDATE_AVAILABLE, "latest", latest, "current", KordonInfo.version(),
                "pluginPage", pluginPage()));
    }

    /** While DISABLED only a successful heartbeat changes the state (no {@code /login} until then). */
    private void setState(ConnectionState next) {
        if (state.get() != ConnectionState.DISABLED) state.set(next);
    }

    /**
     * The panel's Plugin page for this server: as sent by the panel, else derived from the API address and server id
     * ({@link ApiBase#pluginPage}).
     */
    private String pluginPage() {
        ServerSettings s = settings.get();
        if (s.pluginPage() != null && !s.pluginPage().isBlank()) return s.pluginPage();
        Credentials c = credentials.get();
        String apiUrl = c == null ? null : c.apiUrl();
        if (apiUrl == null) return "";
        return ApiBase.pluginPage(apiUrl, installServerId != null ? installServerId : s.serverId());
    }

    private ServerSettings settingsOf(HeartbeatResponse body, ServerSettings previous) {
        HeartbeatResponse.Features f = body.features();
        boolean legacy = f == null;
        HeartbeatResponse.Sync sync = legacy ? null : f.sync();
        SyncPacing pacing = sync == null ? null : SyncPacing.of(SyncPacing.DEFAULT, sync.intervalSec(),
                sync.idleIntervalSec(), sync.snapshotSec(), sync.maxEvents(), sync.maxBodyBytes());
        HeartbeatResponse.Messages m = body.messages();
        HeartbeatResponse.Server server = body.server();
        HeartbeatResponse.Plugin plugin = body.plugin();
        return new ServerSettings(
                server != null && server.id() != null ? server.id() : previous.serverId(),
                server != null && server.name() != null ? server.name() : previous.serverName(),
                body.mode() != null ? body.mode() : previous.mode(),
                body.failOpen() != null ? body.failOpen() : previous.failOpen(),
                new ServerSettings.Messages(m.vpn(), m.blacklist(), m.country(), m.checkFailed(),
                        body.notices() == null ? null : body.notices().blocked() == null ? "" : body.notices().blocked()),
                body.settingsVersion(),
                body.heartbeatIntervalSec() != null ? body.heartbeatIntervalSec()
                        : ServerSettings.DEFAULT_HEARTBEAT_INTERVAL_SEC,
                legacy,
                pacing,
                legacy ? null : f.lists(),
                legacy ? null : f.lookup(),
                plugin == null ? null : plugin.latestVersion(),
                plugin == null ? null : plugin.page(),
                clock.millis());
    }
}
