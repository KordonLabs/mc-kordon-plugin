package com.kordonlabs.plugin.common.login;

import static com.kordonlabs.plugin.common.text.Messages.literal;

import com.kordonlabs.plugin.common.heartbeat.ConnectionWatch;
import com.kordonlabs.plugin.common.platform.Permissions;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.text.Legacy;
import com.kordonlabs.plugin.common.text.LocalizedLine;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.text.ReasonLabels;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Console lines and staff alerts about applied login verdicts.
 *
 * <p>Panel blocks, would-block results in monitor mode and overrides by other plugins are logged; reused verdicts are
 * not repeated. Fallback verdicts are logged at most once a minute per cause, and a printed one for a lost link
 * ({@link #LOSS_CAUSES}) starts a {@link ConnectionWatch} outage. Nicks are inserted as {@link Messages#literal}, so
 * an offline nick with {@code &} codes is neither coloured in staff chat nor altered in the console.
 *
 * <p>Staff notifications use the panel's line ({@link LoginVerdict#notice}, or the variant in a staff member's client
 * language from {@link LoginVerdict#notices}); no line means the admin turned them off. Blocked joins are limited to
 * a burst per window, followed by one summary per minute while blocks keep coming; allowed joins beyond the burst are
 * dropped. Chat is sent on the events executor.
 *
 * <p>{@link #report} runs on a login thread or the login timer and returns at once: no I/O besides
 * {@link ThrottledLog} and no third-party code.
 */
public final class LoginReporter {

    static final int NOTIFY_BURST = 5;
    static final Duration NOTIFY_WINDOW = Duration.ofSeconds(10);
    static final Duration SUMMARY_EVERY = Duration.ofMinutes(1);
    /** Fallback causes meaning the panel could not be reached or refused this proxy. */
    static final Set<String> LOSS_CAUSES = Set.of(LoginGate.CAUSE_TIMEOUT, LoginGate.CAUSE_NETWORK,
            LoginGate.CAUSE_SERVER_ERROR, LoginGate.CAUSE_UNAUTHORIZED, LoginGate.CAUSE_UNSUPPORTED,
            LoginGate.CAUSE_REDIRECT);

    private final Platform platform;
    private final Messages messages;
    private final ThrottledLog log;
    private final Executor events;
    private final Clock clock;
    private final Scheduler later;
    /** May be null. */
    private final ConnectionWatch watch;

    // Staff alert rate limit (guarded by this).
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private final ArrayDeque<Long> joinsSent = new ArrayDeque<>();
    private boolean flooding;
    private int suppressed;

    public LoginReporter(Platform platform, Messages messages, ThrottledLog log, Executor events, Clock clock) {
        this(platform, messages, log, events, clock, (ConnectionWatch) null);
    }

    /** {@code watch} is told about every printed loss line ({@link #LOSS_CAUSES}); may be null. */
    public LoginReporter(Platform platform, Messages messages, ThrottledLog log, Executor events, Clock clock,
                         ConnectionWatch watch) {
        this(platform, messages, log, events, clock, delayedOn(events), watch);
    }

    LoginReporter(Platform platform, Messages messages, ThrottledLog log, Executor events, Clock clock,
                  Scheduler later) {
        this(platform, messages, log, events, clock, later, null);
    }

    LoginReporter(Platform platform, Messages messages, ThrottledLog log, Executor events, Clock clock,
                  Scheduler later, ConnectionWatch watch) {
        this.watch = watch;
        this.platform = platform;
        this.messages = messages;
        this.log = log;
        this.events = guarded(events);
        this.clock = clock;
        this.later = later;
    }

    /** Logs and alerts about an applied verdict. Never blocks, never throws. */
    public void report(LoginAttempt attempt, LoginVerdict verdict) {
        if (attempt == null || verdict == null) return;
        try {
            switch (verdict.source()) {
                case PANEL -> {
                    if (!verdict.allow()) {
                        log.info(messages.console(MessageKey.LOG_BLOCKED,
                                "name", literal(attempt.name()), "ip", attempt.ip(), "reasons", consoleReasons(verdict)));
                        if (verdict.notice() != null) notifyBlocked(staffLine(verdict));
                    } else {
                        if (verdict.wouldBlock()) {
                            log.info(messages.console(MessageKey.LOG_WOULD_BLOCK,
                                    "name", literal(attempt.name()), "ip", attempt.ip(), "reasons", consoleReasons(verdict)));
                        }
                        if (verdict.notice() != null) notifyJoin(staffLine(verdict));
                    }
                }
                case OVERRIDDEN -> {
                    if (verdict.allow()) {
                        log.info(messages.console(MessageKey.LOG_OVERRIDDEN,
                                "name", literal(attempt.name()), "ip", attempt.ip(), "reasons", consoleReasons(verdict)));
                    }
                }
                case FAIL_OPEN, FAIL_CLOSED -> fallback(attempt, verdict);
                default -> {
                    // REUSED (its first attempt was reported), ACCOUNT_DISABLED (the heartbeat logs it) and
                    // NOT_CONFIGURED: nothing to say per player.
                }
            }
        } catch (RuntimeException e) {
            log.debug(() -> "login report failed: " + e);
        }
    }

    /** Debug log for an answer that came after the watchdog had already applied the fallback. */
    public void lateVerdict(LoginAttempt attempt) {
        if (attempt != null) log.debug(() -> messages.console(MessageKey.LOG_LATE_VERDICT, "name",
                literal(attempt.name())));
    }

    private void fallback(LoginAttempt attempt, LoginVerdict verdict) {
        String cause = verdict.reasons().isEmpty() ? LoginGate.CAUSE_TIMEOUT : verdict.reasons().get(0);
        if (LoginGate.CAUSE_OVERLOAD.equals(cause)) {
            log.warn("login_overload", messages.console(MessageKey.LOG_LOGIN_OVERLOAD,
                    "n", LoginGate.MAX_IN_FLIGHT, "name", literal(attempt.name())));
        } else if (!LoginGate.CAUSE_BAD_REQUEST.equals(cause)) { // a 400 is already logged by LoginGate
            boolean open = verdict.source() == LoginVerdict.Source.FAIL_OPEN;
            boolean printed = log.warn((open ? "fail_open:" : "fail_closed:") + cause, messages.console(
                    open ? MessageKey.LOG_FAIL_OPEN : MessageKey.LOG_FAIL_CLOSED,
                    "cause", messages.english().getOr("cause." + cause, cause), "name", literal(attempt.name())));
            if (watch != null && LOSS_CAUSES.contains(cause)) watch.lost(printed);
        }
        if (!verdict.allow()) {
            String panelLine = verdict.notice();
            if (panelLine == null) {
                notifyBlocked(LocalizedLine.of(messages.chat(MessageKey.NOTIFY_BLOCKED, "name", literal(attempt.name()),
                        "reasons", messages.get(MessageKey.REASON_CHECK_FAILED))));
            } else if (!panelLine.isEmpty()) {
                notifyBlocked(LocalizedLine.of(Legacy.translate(panelLine)));
            }
        }
    }

    /** The panel's staff line and its other languages with {@code §} codes; {@code notice} must not be null. */
    private static LocalizedLine staffLine(LoginVerdict v) {
        return new LocalizedLine(v.notice(), v.notices()).map(Legacy::translate);
    }

    /** The reasons in English for the console, such as "VPN, hosting". */
    private String consoleReasons(LoginVerdict v) {
        return ReasonLabels.render(v, messages.english());
    }

    // ── staff chat, rate-limited ─────────────────────────────────────────────────────────────────────────────────

    /** A blocked join; counts as one alert whatever the languages. */
    private void notifyBlocked(LocalizedLine line) {
        boolean startSummary = false;
        synchronized (this) {
            if (flooding) {
                suppressed++;
                return;
            }
            long now = clock.nanos();
            while (!sent.isEmpty() && now - sent.peekFirst() >= NOTIFY_WINDOW.toNanos()) sent.pollFirst();
            if (sent.size() >= NOTIFY_BURST) {
                flooding = true;
                suppressed = 1;
                startSummary = true;
            } else {
                sent.addLast(now);
            }
        }
        if (startSummary) {
            later.schedule(this::summary, SUMMARY_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            events.execute(() -> platform.notifyStaff(Permissions.NOTIFY, line));
        }
    }

    /** A join the panel let in; alerts beyond the burst are dropped. */
    private void notifyJoin(LocalizedLine line) {
        synchronized (this) {
            long now = clock.nanos();
            while (!joinsSent.isEmpty() && now - joinsSent.peekFirst() >= NOTIFY_WINDOW.toNanos()) joinsSent.pollFirst();
            if (joinsSent.size() >= NOTIFY_BURST) return;
            joinsSent.addLast(now);
        }
        events.execute(() -> platform.notifyStaff(Permissions.NOTIFY, line));
    }

    /** Once a minute while flooding, reports how many blocks were not shown; a quiet minute ends the flood. */
    private void summary() {
        int n;
        synchronized (this) {
            n = suppressed;
            suppressed = 0;
            if (n == 0) {
                flooding = false;
                sent.clear();
                return;
            }
        }
        String line = messages.chat(MessageKey.NOTIFY_SUMMARY, "n", n);
        events.execute(() -> platform.notifyStaff(Permissions.NOTIFY, line));
        later.schedule(this::summary, SUMMARY_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Executor guarded(Executor executor) {
        return task -> {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException stopping) {
                // Kordon is stopping: the alert is dropped.
            }
        };
    }

    /** The production summary timer: the JDK's shared delayer hands the task to {@code events}, so no extra thread. */
    private static Scheduler delayedOn(Executor events) {
        Executor target = guarded(events);
        return new Scheduler() {
            @Override
            public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
                AtomicBoolean cancelled = new AtomicBoolean();
                CompletableFuture.delayedExecutor(delay, unit, target).execute(() -> {
                    if (!cancelled.get()) task.run();
                });
                return () -> cancelled.compareAndSet(false, true);
            }

            @Override
            public void execute(Runnable task) {
                target.execute(task);
            }
        };
    }
}
