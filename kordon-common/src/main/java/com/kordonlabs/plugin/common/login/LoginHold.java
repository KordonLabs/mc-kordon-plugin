package com.kordonlabs.plugin.common.login;

import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform-agnostic "hold the login until Kordon answers" protocol, exactly-once by construction.
 *
 * <p>{@link #hold} schedules a watchdog and starts the check. The verdict and the watchdog race on a CAS; the winner
 * applies its verdict and releases the login. The verdict path runs on the login executor (never the HTTP or JDK
 * delayer thread) and fires the public event before the CAS, so a slow third-party handler only delays its own
 * verdict while the watchdog, on its own thread, still fires on time. A verdict arriving after the watchdog does not
 * fire the event at all. The watchdog applies the fallback without events or I/O.
 *
 * <p>A failing {@code apply} never skips {@code release}. {@link #releaseAll()} completes every pending hold at stop,
 * and a {@code hold} that still arrives afterwards gets the fallback at once, because the executors are shut down next
 * and its watchdog or completion might never run.
 */
public final class LoginHold {

    public static final long WATCHDOG_MILLIS = 3_500;
    public static final Duration WATCHDOG = Duration.ofMillis(WATCHDOG_MILLIS);

    private final LoginGate gate;
    private final LoginReporter reporter;
    private final Scheduler loginTimer;
    private final Executor loginExecutor;
    private final ThrottledLog log;
    private final Set<Hold> pending = ConcurrentHashMap.newKeySet();
    /** Set by {@link #releaseAll()} before it walks {@link #pending}; a later {@link #hold} falls back at once. */
    private volatile boolean closed;

    public LoginHold(LoginGate gate, LoginReporter reporter, Scheduler loginTimer, Executor loginExecutor,
                     ThrottledLog log) {
        this.gate = gate;
        this.reporter = reporter;
        this.loginTimer = loginTimer;
        this.loginExecutor = loginExecutor;
        this.log = log;
    }

    /** Platform callbacks for one held login. */
    public interface Target {

        /** Fires the public event (third-party code), which may override the verdict. Login executor only. */
        LoginVerdict fireChecked(LoginVerdict v);

        /** Sets the verdict on the proxy's login event. Must not throw (wrapped anyway). */
        void apply(LoginVerdict finalVerdict);

        /** Lets the proxy continue the login. Called exactly once. */
        void release();
    }

    /** One held login. */
    private static final class Hold {
        final LoginAttempt attempt;
        final Target target;
        final AtomicBoolean done = new AtomicBoolean();
        volatile Scheduler.Cancellable watchdog;

        Hold(LoginAttempt attempt, Target target) {
            this.attempt = attempt;
            this.target = target;
        }
    }

    /** Call after the proxy has been told to wait. Schedules the watchdog and starts the check; returns at once. */
    public void hold(LoginAttempt a, Target target) {
        Hold h = new Hold(a, target);
        pending.add(h);
        // After the add: either releaseAll() (closed first, then the walk) sees this hold, or we see closed here.
        if (closed) {
            if (h.done.compareAndSet(false, true)) finish(h, safeFallback(h), false);
            return;
        }
        try {
            h.watchdog = loginTimer.schedule(() -> watchdog(h), WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
            CompletableFuture<LoginVerdict> verdict = gate.check(a);
            verdict.whenCompleteAsync((v, error) -> verdict(h, v, error), task -> {
                try {
                    loginExecutor.execute(task);
                } catch (RejectedExecutionException stopping) {
                    fallbackNow(h); // no event: this thread may be the HTTP pool or the JDK delayer
                }
            });
        } catch (Throwable t) {
            log.debug(() -> "login hold failed, fallback applied: " + t);
            fallbackNow(h);
        }
    }

    /**
     * At stop: completes every pending hold with the fallback verdict, without reporting it. Holds started afterwards
     * get the same at once inside {@link #hold}.
     */
    public void releaseAll() {
        closed = true;
        for (Hold h : pending.toArray(new Hold[0])) {
            if (h.done.compareAndSet(false, true)) {
                finish(h, safeFallback(h), false);
            }
        }
    }

    /** Holds whose verdict has not been applied yet. */
    public int pending() {
        return pending.size();
    }

    // ── the two racers ───────────────────────────────────────────────────────────────────────────────────────────

    /** On the login executor. */
    private void verdict(Hold h, LoginVerdict v, Throwable error) {
        if (h.done.get()) { // the watchdog (or stop) won: no event for a verdict that cannot apply
            reporter.lateVerdict(h.attempt);
            return;
        }
        LoginVerdict verdict = error != null || v == null ? safeFallback(h) : v;
        LoginVerdict fin = verdict;
        try {
            LoginVerdict fired = h.target.fireChecked(verdict);
            if (fired != null) fin = fired;
        } catch (Throwable t) {
            log.debug(() -> "KordonLoginCheckedEvent failed, the verdict stays: " + t);
        }
        if (h.done.compareAndSet(false, true)) {
            finish(h, fin, true);
        } else {
            reporter.lateVerdict(h.attempt);
        }
    }

    /** On the login timer: no event, no I/O. */
    private void watchdog(Hold h) {
        if (h.done.compareAndSet(false, true)) finish(h, safeFallback(h), true);
    }

    private void fallbackNow(Hold h) {
        if (h.done.compareAndSet(false, true)) finish(h, safeFallback(h), true);
    }

    /** Called by the CAS winner only. */
    private void finish(Hold h, LoginVerdict verdict, boolean report) {
        Scheduler.Cancellable w = h.watchdog;
        if (w != null) w.cancel();
        try {
            h.target.apply(verdict);
        } catch (Throwable t) {
            log.debug(() -> "login verdict not applied: " + t);
        }
        try {
            h.target.release();
        } catch (Throwable t) {
            log.debug(() -> "login intent not released: " + t);
        }
        pending.remove(h);
        if (report) reporter.report(h.attempt, verdict);
    }

    private LoginVerdict safeFallback(Hold h) {
        try {
            return gate.fallback(h.attempt);
        } catch (Throwable t) {
            // Never leave a player hanging: the failOpen default.
            return new LoginVerdict(true, null, LoginVerdict.Source.FAIL_OPEN, null, null,
                    List.of(LoginGate.CAUSE_TIMEOUT), false, null);
        }
    }
}
