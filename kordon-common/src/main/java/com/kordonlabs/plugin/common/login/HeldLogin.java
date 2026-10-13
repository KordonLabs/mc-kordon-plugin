package com.kordonlabs.plugin.common.login;

import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.util.ThrottledLog;

/**
 * One held login as a platform listener sees it: the {@link LoginHold.Target} that applies the verdict to the proxy's
 * event and lets the proxy continue, each at most once and never out of order. A release waits for an apply in
 * progress (both are O(1) under this object's monitor), and an apply after the release is ignored because the proxy
 * has already continued. Platforms only implement how a login is refused ({@link #deny}) and how the proxy continues
 * ({@link #resume}).
 */
public abstract class HeldLogin implements LoginHold.Target {

    /** One login's view of the core, taken before the proxy is told to wait. */
    public interface Gate {

        /** {@link LoginHold#hold}: returns at once; applies and releases through {@code target} exactly once. */
        void hold(LoginAttempt attempt, LoginHold.Target target);

        /** {@link LoginGate#fallback}: the verdict used when the check is unavailable. */
        LoginVerdict fallback(LoginAttempt attempt);

        /** {@link LoginGate#onLoginAllowed}. */
        void allowed(LoginAttempt attempt, LoginVerdict verdict);

        /** {@link LoginGate#onLoginAborted}. */
        void aborted(Object connectionKey);
    }

    /** What a platform's login listener needs from the core. */
    public interface Core {

        /** Null when Kordon does not check logins now: not started, not configured, or stopping. */
        Gate gate();

        /**
         * Fires the platform's public {@code KordonLoginCheckedEvent}. {@code deadlineNanos} is the
         * {@link System#nanoTime()} of this hold's watchdog; a handler answering later cannot change anything.
         */
        LoginVerdict fireChecked(Object connectionKey, LoginVerdict verdict, long deadlineNanos);
    }

    /** The platform's public {@code KordonLoginCheckedEvent} (see {@link Core#fireChecked}). */
    @FunctionalInterface
    public interface Checker {

        LoginVerdict fire(Object connectionKey, LoginVerdict verdict, long deadlineNanos);
    }

    private final LoginAttempt attempt;
    private final Gate gate;
    private final Core core;
    private final ThrottledLog log;
    /** The watchdog deadline, taken here; the hold starts microseconds later. */
    private final long deadlineNanos;
    private boolean applied;  // guarded by this
    private boolean released; // guarded by this

    protected HeldLogin(LoginAttempt attempt, Gate gate, Core core, ThrottledLog log) {
        this.attempt = attempt;
        this.gate = gate;
        this.core = core;
        this.log = log;
        this.deadlineNanos = System.nanoTime() + LoginHold.WATCHDOG.toNanos();
    }

    /** The login being held. */
    public final LoginAttempt attempt() {
        return attempt;
    }

    /** {@link System#nanoTime()} of this hold's watchdog. */
    public final long deadlineNanos() {
        return deadlineNanos;
    }

    @Override
    public final LoginVerdict fireChecked(LoginVerdict verdict) {
        return core.fireChecked(attempt.connectionKey(), verdict, deadlineNanos);
    }

    @Override
    public final synchronized void apply(LoginVerdict verdict) {
        if (verdict == null || applied || released) return;
        applied = true;
        if (verdict.allow()) {
            try {
                gate.allowed(attempt, verdict);
            } catch (Throwable t) {
                warn("listener.login.allowed", "Kordon: could not remember an allowed login: ", t);
            }
            return;
        }
        try {
            deny(verdict.kickMessage());
        } catch (Throwable t) {
            warn("listener.login.deny", "Kordon: could not refuse a login: ", t);
        }
        try {
            gate.aborted(attempt.connectionKey());
        } catch (Throwable t) {
            warn("listener.login.aborted", "Kordon: could not forget a refused login: ", t);
        }
    }

    @Override
    public final void release() {
        synchronized (this) {
            if (released) return;
            released = true;
        }
        resume(); // outside the monitor: the proxy may continue the login on this very thread
    }

    /** The hold failed: the fallback verdict (if it can be computed) is applied and the proxy continues now. */
    public final void fail(Throwable error) {
        warn("listener.login.hold", "Kordon: login hold failed, fallback applied: ", error);
        try {
            apply(gate.fallback(attempt));
        } catch (Throwable t) {
            warn("listener.login.fallback", "Kordon: no fallback verdict, login let through: ", t);
        } finally {
            release();
        }
    }

    /**
     * Refuses the login with {@code kickMessage} ({@code §}-codes; may be null). O(1). Must leave the event refused
     * even when the text cannot be built.
     */
    protected abstract void deny(String kickMessage);

    /** Lets the proxy continue this login. Called exactly once. */
    protected abstract void resume();

    /**
     * Never throws: a log that cannot write, or an error whose {@code toString()} fails, must not skip the fallback,
     * {@code gate.aborted} or the release (on Velocity a throwing continuation consumer lets the login in unchecked).
     */
    private void warn(String key, String message, Throwable error) {
        ThrottledLog l = log;
        if (l == null) return;
        try {
            l.warn(key, message + error);
        } catch (Throwable ignored) {
            // nothing left to report with
        }
    }

    /** The production {@link Core}: no gate while the core is stopped, inactive or being stopped. */
    public static Core coreOf(KordonCore core, Checker checker) {
        return new Core() {
            @Override
            public Gate gate() {
                if (core.stopped() || !core.active()) return null;
                LoginGate loginGate = core.loginGate();
                LoginHold loginHold = core.loginHold();
                if (loginGate == null || loginHold == null) return null; // stop() cleared them meanwhile
                return gateOf(loginGate, loginHold);
            }

            @Override
            public LoginVerdict fireChecked(Object connectionKey, LoginVerdict verdict, long deadlineNanos) {
                return checker.fire(connectionKey, verdict, deadlineNanos);
            }
        };
    }

    /** The production {@link Gate}: the core's login gate and hold. */
    public static Gate gateOf(LoginGate loginGate, LoginHold loginHold) {
        return new Gate() {
            @Override
            public void hold(LoginAttempt attempt, LoginHold.Target target) {
                loginHold.hold(attempt, target);
            }

            @Override
            public LoginVerdict fallback(LoginAttempt attempt) {
                return loginGate.fallback(attempt);
            }

            @Override
            public void allowed(LoginAttempt attempt, LoginVerdict verdict) {
                loginGate.onLoginAllowed(attempt, verdict);
            }

            @Override
            public void aborted(Object connectionKey) {
                loginGate.onLoginAborted(connectionKey);
            }
        };
    }
}
