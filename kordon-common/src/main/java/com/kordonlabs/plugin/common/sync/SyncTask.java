package com.kordonlabs.plugin.common.sync;

import com.kordonlabs.plugin.common.api.ApiResult;
import com.kordonlabs.plugin.common.api.KordonApi;
import com.kordonlabs.plugin.common.api.dto.SyncEvent;
import com.kordonlabs.plugin.common.api.dto.SyncRequest;
import com.kordonlabs.plugin.common.api.dto.SyncResponse;
import com.kordonlabs.plugin.common.heartbeat.ConnectionWatch;
import com.kordonlabs.plugin.common.install.ApiBase;
import com.kordonlabs.plugin.common.install.Credentials;
import com.kordonlabs.plugin.common.kick.KickExecutor;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.session.SessionEvent;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.session.SyncBatch;
import com.kordonlabs.plugin.common.state.ConnectionState;
import com.kordonlabs.plugin.common.state.ConnectionStateHolder;
import com.kordonlabs.plugin.common.state.ServerSettings;
import com.kordonlabs.plugin.common.state.ServerSettings.SyncPacing;
import com.kordonlabs.plugin.common.state.SettingsHolder;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.Backoff;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The background channel to {@code POST /api/v1/mc/sync}: session events and the online snapshot go up; kicks,
 * resync instructions and pacing come down.
 *
 * <p>Single flight on the worker: at most one request is in flight and one run is pending. Each run takes an atomic
 * batch from the tracker (sweeping ghost sessions first when a snapshot is due), sends it and handles the answer back
 * on the worker. Timing comes from {@link Pacing}; {@link #nudge} pulls the pending run in when events are queued.
 *
 * <p>Sends only while the last successful heartbeat advertised sync and the link is OK or UNREACHABLE; in the latter
 * case the sync's own backoff applies, so a failed heartbeat alone does not stall the queue. Otherwise, and while
 * paused after a 401 or 403, the loop only checks again every idle interval, and a successful heartbeat resumes it
 * ({@link #onHeartbeat}).
 *
 * <p>Events leave the queue only after a successful answer. Network errors, timeouts, 5xx, 404 or non-JSON answers
 * (a redeploy) and redirects (never followed) back off with the queue kept; 429 waits at least {@code Retry-After};
 * 400 drops the batch and requests a snapshot; 413 shrinks the batches. A printed failure warning starts a
 * {@link ConnectionWatch} outage, which the next successful sync ends.
 */
public final class SyncTask {

    /** A sync whose future never completes is given up after this (the API's own timeout is shorter). */
    static final Duration REQUEST_GUARD = Duration.ofSeconds(30);
    /** Batch budget with the default body limit (480 KiB of 512 KiB). */
    static final int BATCH_MAX_BYTES = 480 * 1024;
    static final int MIN_BATCH_BYTES = 32 * 1024;
    static final int MIN_MAX_EVENTS = 50;
    /** The panel's snapshot limit: with more tracked sessions no snapshot is sent, since a 400 would drop the batch. */
    static final int MAX_SNAPSHOT = 10_000;
    /** A successful heartbeat that resumes sync runs it this soon. */
    static final long RESUME_MS = 1_000;
    /** After a 413 the smaller batch is sent this soon. */
    static final long RETRY_SMALLER_MS = 1_000;

    public static final String LOG_SYNC_FAILING = "log.sync_failing";
    static final String LOG_PANEL_REDIRECT = "log.panel_redirect";
    static final String LOG_QUEUE_OVERFLOW = "log.queue_overflow";

    private static final long NO_NUDGE = Long.MAX_VALUE;

    /** What the pending timer will do. */
    private enum Next {
        /** A normal run: nudges may pull it in. */
        RUN,
        /** A retry after a failure: never shortened by nudges, only reset by a successful heartbeat. */
        BACKOFF,
        /** Sync is off, paused or not yet advertised: look again later, send nothing. */
        CHECK
    }

    private final KordonApi api;
    private final SessionTracker sessions;
    private final KickExecutor kicks;
    private final SettingsHolder settings;
    private final ConnectionStateHolder state;
    private final Supplier<Credentials> credentials;
    private final ConnectionWatch watch;
    private final Platform platform;
    private final Messages messages;
    private final ThrottledLog log;
    private final Scheduler worker;
    private final Clock clock;
    private final String instance;
    private final Runnable heartbeatNow;

    private final Object lock = new Object();
    // Guarded by lock.
    private final Pacing pacing = new Pacing();
    private final Backoff backoff;
    private boolean started;
    private boolean halted;
    private boolean running;
    private boolean paused;
    /** A successful heartbeat said the panel has no sync: queued events are dropped. */
    private boolean featureOff;
    /** A successful heartbeat advertised sync. */
    private boolean advertised;
    private SyncPacing heartbeatPacing;
    private Scheduler.Cancellable pending;
    private long pendingDueNanos;
    private Next pendingKind;
    private long scheduleSeq;
    private long nudgeDeadlineNanos = NO_NUDGE;
    private int maxEventsCap = Integer.MAX_VALUE;
    private int maxBytesCap = BATCH_MAX_BYTES;
    /**
     * The request sent last, until its answer has been handled (kept after {@link #halt}). {@link #finalSync} waits for
     * it, so the stopping request never carries the same events concurrently.
     */
    private Attempt lastAttempt;

    // Worker only.
    private String requestedVersion;
    private long lastDropped;
    private boolean failing;

    /**
     * @param credentials  the Kordon API address and key in use (only the address is used, for log lines)
     * @param watch        reports restored connections; may be null
     * @param instance     this instance UUID
     * @param heartbeatNow asks for an immediate heartbeat, for example when the settings version changed
     */
    public SyncTask(KordonApi api, SessionTracker sessions, KickExecutor kicks, SettingsHolder settings,
                    ConnectionStateHolder state, Supplier<Credentials> credentials, ConnectionWatch watch,
                    Platform platform, Messages messages, ThrottledLog log, Scheduler worker, Clock clock,
                    String instance, Runnable heartbeatNow) {
        this(api, sessions, kicks, settings, state, credentials, watch, platform, messages, log, worker, clock,
                instance, heartbeatNow, new Backoff());
    }

    SyncTask(KordonApi api, SessionTracker sessions, KickExecutor kicks, SettingsHolder settings,
             ConnectionStateHolder state, Supplier<Credentials> credentials, ConnectionWatch watch, Platform platform,
             Messages messages, ThrottledLog log, Scheduler worker, Clock clock, String instance,
             Runnable heartbeatNow, Backoff backoff) {
        this.api = Objects.requireNonNull(api, "api");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.kicks = Objects.requireNonNull(kicks, "kicks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.state = Objects.requireNonNull(state, "state");
        this.credentials = credentials == null ? () -> Credentials.NONE : credentials;
        this.watch = watch;
        this.platform = Objects.requireNonNull(platform, "platform");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.log = Objects.requireNonNull(log, "log");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instance = Objects.requireNonNull(instance, "instance");
        this.heartbeatNow = heartbeatNow == null ? () -> { } : heartbeatNow;
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    /** Arms the loop; it only sends once a heartbeat advertised {@code features.sync}. Called once. */
    public void start() {
        synchronized (lock) {
            if (started || halted) return;
            started = true;
            if (sendable()) schedule(RESUME_MS, Next.RUN);
            else schedule(pacing.idleIntervalMillis(), Next.CHECK);
        }
    }

    /**
     * An event was enqueued: pulls the pending run forward as {@link Pacing#nudgeDelayMillis} says, but never shortens
     * a backoff. Any thread; returns at once.
     */
    public void nudge(SessionEvent.Kind kind) {
        if (kind == null) return;
        int queued = sessions.queued();
        long now = clock.nanos();
        synchronized (lock) {
            if (!started || halted) return;
            long target = now + pacing.nudgeDelayMillis(kind, queued) * 1_000_000L;
            if (inFlight()) {
                nudgeDeadlineNanos = Math.min(nudgeDeadlineNanos, target);
                return;
            }
            if (pending == null || pendingKind != Next.RUN || pendingDueNanos <= target) return;
            schedule(Math.max(0, (target - now) / 1_000_000L), Next.RUN);
        }
    }

    /**
     * A successful heartbeat, on the worker. When sync is advertised, applies its pacing and resumes after a pause, a
     * backoff or while sync was off. When it is not, sync is switched off and queued events are dropped; this is
     * logged unless the whole panel is an older one, which the heartbeat already logs.
     */
    public void onHeartbeat(ServerSettings settings) {
        if (settings == null) return;
        SyncPacing advertisedPacing = settings.sync();
        boolean clear = false;
        boolean logOff = false;
        synchronized (lock) {
            if (halted) return;
            if (advertisedPacing == null) {
                if (!featureOff) {
                    featureOff = true;
                    clear = true;
                    logOff = advertised && !settings.legacyPanel();
                    advertised = false;
                    pacing.resetSnapshot();
                }
            } else {
                boolean resume = featureOff || !advertised || paused || backoff.active() || pendingKind == Next.CHECK;
                featureOff = false;
                advertised = true;
                paused = false;
                applyHeartbeatPacing(advertisedPacing);
                if (resume) {
                    backoff.reset();
                    if (started && !inFlight()) {
                        long due = clock.nanos() + RESUME_MS * 1_000_000L;
                        if (pending == null || pendingKind != Next.RUN || pendingDueNanos > due) {
                            schedule(RESUME_MS, Next.RUN);
                        }
                    }
                }
            }
        }
        if (clear) sessions.clearQueue();
        if (logOff) {
            log.warn(messages.console(MessageKey.LOG_FEATURE_OFF, "feature", messages.english().get(MessageKey.FEATURE_SYNC)));
        }
    }

    /**
     * First stop step: cancels the pending run and schedules nothing more; a run that has not sent yet never sends. A
     * request already in flight may complete; {@link #finalSync} waits for it.
     */
    public void halt() {
        synchronized (lock) {
            halted = true;
            cancelPending();
        }
    }

    /**
     * At stop, on the caller thread, blocking at most {@code budget}: if sync is enabled, sends one final request with
     * the remaining quits, the open sids when the batch drains the queue, and {@code stopping: true}; then gives up
     * silently. Returns true when the panel answered successfully.
     *
     * <p>A regular request still in flight is waited for first, for at most half the budget, so the final request
     * carries only the rest. Sent together, the panel would handle the same events twice, and a regular request it
     * finished after the stopping one would mark the stopped instance alive again.
     */
    public boolean finalSync(Duration budget) {
        halt();
        if (budget == null || budget.isNegative() || budget.isZero()) return false;
        long deadline = System.nanoTime() + budget.toNanos();
        int maxEvents;
        int maxBytes;
        Attempt previous;
        synchronized (lock) {
            if (!started || paused || featureOff || !sendable()) return false;
            maxEvents = maxEvents();
            maxBytes = maxBytes();
            previous = lastAttempt;
        }
        if (previous != null) awaitInFlight(previous, budget.toNanos() / 2);
        boolean fits = sessions.size() <= MAX_SNAPSHOT;
        long takenAt = clock.nanos();
        SyncBatch batch = sessions.takeBatch(maxEvents, maxBytes, fits);
        SyncRequest request = request(batch, takenAt, true);
        try {
            long left = Math.max(1, deadline - System.nanoTime());
            ApiResult<SyncResponse> result = api.sync(request).get(left, TimeUnit.NANOSECONDS);
            if (result != null && result.ok()) return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.debug(() -> "Kordon: final sync not answered: " + e);
        }
        return false;
    }

    /**
     * During the stop, on the caller thread: waits for the answer of the request sent before {@link #halt}, whose
     * worker handler does nothing once halted. Success commits its events; otherwise they are left for the final
     * request.
     */
    private void awaitInFlight(Attempt a, long maxNanos) {
        try {
            ApiResult<SyncResponse> result = a.answer().get(Math.max(1, maxNanos), TimeUnit.NANOSECONDS);
            if (result != null && result.ok() && result.body() != null) commitOnce(a);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.debug(() -> "Kordon: the last sync did not answer before the final one: " + e);
        }
    }

    // ── the loop ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One request's context, from the batch to the handled answer. {@code answer} completes with the HTTP result, not
     * the guard's; {@code committed} ensures the batch is committed once, on the worker or in {@link #finalSync}.
     */
    private record Attempt(SyncBatch batch, boolean snapshotDue, long takenAtNanos, int queuedBefore,
                           AtomicBoolean handled, Scheduler.Cancellable[] guard,
                           CompletableFuture<ApiResult<SyncResponse>> answer, AtomicBoolean committed) {
    }

    /** Removes the attempt's events from the queue after a successful answer, at most once. */
    private void commitOnce(Attempt a) {
        if (a.committed().compareAndSet(false, true)) sessions.commit(a.batch().drained());
    }

    /** Under the lock: replaces the pending run. */
    private void schedule(long delayMillis, Next kind) {
        if (halted) return;
        cancelPending();
        long seq = ++scheduleSeq;
        long delay = Math.max(0, delayMillis);
        pendingKind = kind;
        pendingDueNanos = clock.nanos() + delay * 1_000_000L;
        pending = worker.schedule(() -> fire(seq), delay, TimeUnit.MILLISECONDS);
    }

    private void cancelPending() {
        if (pending != null) pending.cancel();
        pending = null;
        pendingKind = null;
    }

    /**
     * Under the lock: a request is in flight and its answer will decide the next run. Once that answer has scheduled
     * the next run, {@code running} stays true until {@link #complete} returns, and nudges and heartbeats act on the
     * scheduled run; otherwise a join could wait for the idle tick and a heartbeat for the whole backoff.
     */
    private boolean inFlight() {
        return running && pending == null;
    }

    private void fire(long seq) {
        synchronized (lock) {
            if (seq != scheduleSeq || halted || running) return;
            pending = null;
            pendingKind = null;
            running = true;
            nudgeDeadlineNanos = NO_NUDGE;
        }
        boolean inFlight = false;
        try {
            inFlight = runOnce();
        } catch (RuntimeException e) {
            log.warn("sync.run", "Kordon: sync run failed: " + e);
            synchronized (lock) {
                schedule(backoff.next().toMillis(), Next.BACKOFF);
            }
        } finally {
            if (!inFlight) {
                synchronized (lock) {
                    running = false;
                }
            }
        }
    }

    /** On the worker. True when a request is in flight (its answer ends the run); false when the next run is set. */
    private boolean runOnce() {
        boolean off;
        synchronized (lock) {
            if (halted) return false; // halt() came after fire() passed its check: the final sync sends instead
            off = paused || featureOff || !sendable();
            if (off) schedule(pacing.idleIntervalMillis(), Next.CHECK);
        }
        if (off) {
            if (featureOffNow()) sessions.clearQueue(); // nothing will be sent: do not let the queue fill up
            else reportOverflow();
            return false;
        }
        reportOverflow();

        long now = clock.nanos();
        boolean requested = sessions.snapshotRequested();
        boolean fits = sessions.size() <= MAX_SNAPSHOT;
        boolean snapshotDue;
        int maxEvents;
        int maxBytes;
        synchronized (lock) {
            applyHeartbeatPacing(settings.get().sync());
            snapshotDue = (pacing.snapshotDue(now) || requested) && fits;
            maxEvents = maxEvents();
            maxBytes = maxBytes();
        }
        if (snapshotDue) sessions.sweepGhosts(platform.onlinePlayers());
        int queuedBefore = sessions.queued();
        long takenAt = clock.nanos();
        SyncBatch batch = sessions.takeBatch(maxEvents, maxBytes, snapshotDue);
        SyncRequest request = request(batch, takenAt, false);

        Attempt attempt = new Attempt(batch, snapshotDue, takenAt, queuedBefore, new AtomicBoolean(),
                new Scheduler.Cancellable[1], new CompletableFuture<>(), new AtomicBoolean());
        synchronized (lock) {
            // Checked again while registering the attempt: either finalSync sees and waits for this request, or it is
            // never sent. The held prefix is taken again by the final sync's batch.
            if (halted) return false;
            lastAttempt = attempt;
        }
        attempt.guard()[0] = worker.schedule(() -> complete(attempt, ApiResult.noAnswer(ApiResult.Outcome.TIMEOUT)),
                REQUEST_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<ApiResult<SyncResponse>> future;
        try {
            future = api.sync(request);
        } catch (RuntimeException e) {
            future = CompletableFuture.completedFuture(ApiResult.noAnswer(ApiResult.Outcome.NETWORK));
        }
        future.whenComplete((result, error) -> {
            ApiResult<SyncResponse> answer = error != null || result == null
                    ? ApiResult.noAnswer(ApiResult.Outcome.NETWORK) : result;
            attempt.answer().complete(answer);
            worker.execute(() -> complete(attempt, answer));
        });
        return true;
    }

    /** On the worker: handles the answer (or the guard timeout) of one attempt and decides the next run. */
    private void complete(Attempt a, ApiResult<SyncResponse> result) {
        if (!a.handled().compareAndSet(false, true)) return;
        Scheduler.Cancellable guard = a.guard()[0];
        if (guard != null) guard.cancel();
        synchronized (lock) {
            if (halted) {
                running = false; // stopping: finalSync commits this attempt's events itself
                return;
            }
        }
        try {
            if (result.ok() && result.body() != null) succeeded(a, result.body());
            else failed(a, result);
        } catch (RuntimeException e) {
            log.warn("sync.run", "Kordon: sync answer not handled: " + e);
            synchronized (lock) {
                schedule(backoff.next().toMillis(), Next.BACKOFF);
            }
        } finally {
            synchronized (lock) {
                running = false;
                if (lastAttempt == a) lastAttempt = null;
                if (pending == null && !halted) schedule(pacing.idleIntervalMillis(), Next.CHECK);
            }
        }
    }

    private void succeeded(Attempt a, SyncResponse body) {
        commitOnce(a);
        if (failing) {
            failing = false;
            log.reset(LOG_SYNC_FAILING);
        }
        if (watch != null) watch.restored();
        int newKicks = body.kicks().isEmpty() ? 0 : kicks.submit(body.kicks());
        SyncResponse.Resync resync = body.resync();
        if (!resync.unknown().isEmpty() || !resync.closed().isEmpty()) sessions.resync(resync.unknown(), resync.closed());
        checkSettingsVersion(body.settingsVersion());

        boolean backlog = a.queuedBefore() > a.batch().drained();
        boolean snapshotPending = a.snapshotDue() && a.batch().onlineSids() == null && !backlog;
        boolean active = sessions.size() > 0 || sessions.queued() > 0 || platform.onlineCount() > 0;
        synchronized (lock) {
            backoff.reset();
            if (a.batch().onlineSids() != null) pacing.snapshotSent(a.takenAtNanos());
            pacing.apply(body.syncIntervalSec(), body.idleSyncIntervalSec(), body.snapshotSec());
            long nudge = nudgeDeadlineNanos == NO_NUDGE ? -1
                    : Math.max(0, (nudgeDeadlineNanos - clock.nanos()) / 1_000_000L);
            schedule(pacing.afterSuccessMillis(backlog, snapshotPending, newKicks > 0, active, nudge), Next.RUN);
        }
    }

    private void failed(Attempt a, ApiResult<SyncResponse> result) {
        SyncBatch batch = a.batch();
        ApiResult.Outcome outcome = result.ok() ? ApiResult.Outcome.UNSUPPORTED : result.outcome();
        switch (outcome) {
            case UNAUTHORIZED, ACCOUNT_DISABLED, NOT_CONFIGURED -> {
                // The heartbeat reports these; its next success resumes sync.
                keep(batch);
                synchronized (lock) {
                    paused = true;
                    schedule(pacing.idleIntervalMillis(), Next.CHECK);
                }
                log.debug(() -> "Kordon: sync paused (" + outcome + ") until the heartbeat succeeds");
                // Let the heartbeat report a refused key at once rather than at its next tick.
                if (outcome == ApiResult.Outcome.UNAUTHORIZED) heartbeatNow.run();
            }
            case BAD_REQUEST -> {
                // The panel rejected the envelope: never let one batch poison the queue.
                sessions.commit(batch.drained());
                sessions.requestSnapshot();
                retryLater(MessageKey.CAUSE_BAD_REQUEST, 0);
            }
            case TOO_LARGE -> tooLarge(a);
            case RATE_LIMITED -> {
                keep(batch);
                retryLater(MessageKey.CAUSE_RATE_LIMITED, result.retryAfterSec());
            }
            case REDIRECT -> {
                keep(batch);
                long delay = retryLater(MessageKey.CAUSE_REDIRECT, 0);
                lost(log.warn(LOG_PANEL_REDIRECT, messages.console(MessageKey.LOG_PANEL_REDIRECT, "apiUrl", apiOrigin(),
                        "location", result.redirectOrigin() == null ? "?" : result.redirectOrigin(),
                        "panelUrl", ApiBase.pagesUrl(credentials.get().apiUrl()))));
                log.debug(() -> "Kordon: sync redirected, next try in " + delay + " ms");
            }
            case SERVER_ERROR -> {
                keep(batch);
                retryLater(MessageKey.CAUSE_SERVER_ERROR, 0);
            }
            case NETWORK, TIMEOUT -> {
                keep(batch);
                retryLater(MessageKey.CAUSE_NETWORK, 0);
            }
            default -> {
                // 404 or non-JSON, as during a redeploy: transient, so the queue is kept.
                keep(batch);
                retryLater(MessageKey.CAUSE_UNSUPPORTED, 0);
            }
        }
    }

    /** 413: halves the batch limits down to a floor; once at the floor, the batch is dropped. */
    private void tooLarge(Attempt a) {
        SyncBatch batch = a.batch();
        boolean hadSnapshot = batch.onlineSids() != null;
        boolean shrunk;
        synchronized (lock) {
            int events = maxEvents();
            int bytes = maxBytes();
            maxEventsCap = Math.max(MIN_MAX_EVENTS, events / 2);
            maxBytesCap = Math.max(MIN_BATCH_BYTES, bytes / 2);
            shrunk = maxEventsCap < events || maxBytesCap < bytes;
            // A snapshot cannot be split: this cycle's is given up and the next one may go alone.
            if (hadSnapshot) pacing.snapshotSent(a.takenAtNanos());
        }
        if (batch.events().isEmpty()) {
            retryLater(MessageKey.CAUSE_TOO_LARGE, 0);
        } else if (hadSnapshot || shrunk) {
            sessions.release();
            logFailing(MessageKey.CAUSE_TOO_LARGE, RETRY_SMALLER_MS);
            synchronized (lock) {
                schedule(RETRY_SMALLER_MS, Next.BACKOFF);
            }
        } else {
            sessions.commit(batch.drained());
            sessions.requestSnapshot();
            retryLater(MessageKey.CAUSE_TOO_LARGE, 0);
        }
    }

    /** A failed request: its events stay queued; a snapshot it carried is sent again. */
    private void keep(SyncBatch batch) {
        sessions.release();
        if (batch.onlineSids() != null) sessions.requestSnapshot();
    }

    /** Schedules a backoff retry, never sooner than {@code Retry-After}, and logs a throttled warning. */
    private long retryLater(MessageKey cause, int retryAfterSec) {
        long delay;
        synchronized (lock) {
            delay = backoff.next(retryAfterSec).toMillis();
            schedule(delay, Next.BACKOFF);
        }
        logFailing(cause, delay);
        return delay;
    }

    private void logFailing(MessageKey cause, long delayMillis) {
        failing = true;
        lost(log.warn(LOG_SYNC_FAILING, messages.console(MessageKey.LOG_SYNC_FAILING, "cause",
                messages.english().get(cause), "sec", Math.max(1, (delayMillis + 999) / 1000))));
    }

    /** A loss warning was emitted; {@code printed} is false when it was throttled away. */
    private void lost(boolean printed) {
        if (watch != null) watch.lost(printed);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private SyncRequest request(SyncBatch batch, long takenAtNanos, boolean stopping) {
        long now = clock.nanos();
        List<SyncEvent> events = new ArrayList<>(batch.events().size());
        for (SessionEvent e : batch.events()) events.add(e.toWire(now));
        SyncRequest.Online online = batch.onlineSids() == null ? null
                : new SyncRequest.Online(SessionEvent.agoMillis(takenAtNanos, now), batch.onlineSids());
        return new SyncRequest(instance, events, online, stopping);
    }

    /** Under the lock: a heartbeat advertised sync, and the link is neither refused nor unconfigured. */
    private boolean sendable() {
        if (!advertised || settings.get().sync() == null) return false;
        ConnectionState s = state.get();
        return s == ConnectionState.OK || s == ConnectionState.UNREACHABLE;
    }

    private boolean featureOffNow() {
        synchronized (lock) {
            return featureOff;
        }
    }

    /** Under the lock: a changed heartbeat pacing becomes the base again; sync answers tune it from there. */
    private void applyHeartbeatPacing(SyncPacing fromHeartbeat) {
        if (fromHeartbeat != null && fromHeartbeat != heartbeatPacing) {
            heartbeatPacing = fromHeartbeat;
            pacing.apply(fromHeartbeat);
        }
    }

    /** Under the lock. */
    private int maxEvents() {
        return Math.min(pacing.current().maxEvents(), maxEventsCap);
    }

    /** Under the lock: 15/16 of the body limit, or less after a 413. */
    private int maxBytes() {
        return Math.min(Math.min(BATCH_MAX_BYTES, pacing.current().maxBodyBytes() / 16 * 15), maxBytesCap);
    }

    private void reportOverflow() {
        long dropped = sessions.droppedEvents();
        if (dropped > lastDropped) {
            lastDropped = dropped;
            log.warn(LOG_QUEUE_OVERFLOW, messages.console(MessageKey.LOG_QUEUE_OVERFLOW));
        }
    }

    /** A {@code settingsVersion} that differs from the last heartbeat's triggers one immediate heartbeat per value. */
    private void checkSettingsVersion(String version) {
        if (version == null) return;
        if (version.equals(settings.get().settingsVersion())) {
            requestedVersion = null;
            return;
        }
        if (version.equals(requestedVersion)) return;
        requestedVersion = version;
        heartbeatNow.run();
    }

    /** Scheme and authority of the Kordon API address in use, for the redirect warning; {@code "?"} if unknown. */
    private String apiOrigin() {
        String url = credentials.get().apiUrl();
        if (url != null) {
            try {
                URI uri = URI.create(url);
                if (uri.getScheme() != null && uri.getRawAuthority() != null) {
                    return uri.getScheme() + "://" + uri.getRawAuthority();
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return "?";
    }
}
