package com.kordonlabs.plugin.common.login;

import com.kordonlabs.plugin.common.api.ApiResult;
import com.kordonlabs.plugin.common.api.KordonApi;
import com.kordonlabs.plugin.common.api.dto.LoginRequest;
import com.kordonlabs.plugin.common.api.dto.LoginResponse;
import com.kordonlabs.plugin.common.state.ConnectionState;
import com.kordonlabs.plugin.common.state.ConnectionStateHolder;
import com.kordonlabs.plugin.common.state.ServerSettings;
import com.kordonlabs.plugin.common.state.SettingsHolder;
import com.kordonlabs.plugin.common.text.Legacy;
import com.kordonlabs.plugin.common.text.LocalizedLine;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.text.Placeholders;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides one login. No events and no I/O besides at most one {@code /login} request per check, never retried and
 * never queued.
 *
 * <p>A login is allowed without a request when the plugin is not configured or the account is disabled, and reuses a
 * recent verdict when the same player reconnects within a minute ({@link RecentAllows}; some proxies verify a player
 * and then make them reconnect). When too many requests are in flight, or the panel cannot answer properly, the
 * login gets the {@link #fallback} verdict whose single reason is the local cause, which {@link LoginReporter} logs.
 * The returned future never completes exceptionally and completes within {@link #TIMEOUT}. Stages that run
 * third-party code are attached by {@link LoginHold}, never here.
 */
public final class LoginGate {

    /** The most {@code /login} requests in flight at once. */
    public static final int MAX_IN_FLIGHT = 256;
    public static final Duration TIMEOUT = Duration.ofMillis(3000);

    public static final String CAUSE_TIMEOUT = "timeout";
    public static final String CAUSE_NETWORK = "network";
    public static final String CAUSE_SERVER_ERROR = "server_error";
    public static final String CAUSE_BAD_REQUEST = "bad_request";
    public static final String CAUSE_UNAUTHORIZED = "unauthorized";
    public static final String CAUSE_UNSUPPORTED = "unsupported";
    public static final String CAUSE_REDIRECT = "redirect";
    public static final String CAUSE_RATE_LIMITED = "rate_limited";
    public static final String CAUSE_TOO_LARGE = "too_large";
    public static final String CAUSE_OVERLOAD = "overload";

    /** The panel's error message of a 400 is cut to this length in the log. */
    static final int MAX_REJECTION_TEXT = 200;
    /** {@code kordon_base:<category>} values that are connection types; their kick text is the VPN one. */
    static final Set<String> BASE_CONNECTION_CATEGORIES = Set.of("vpn", "proxy", "resproxy", "hosting");
    static final String LOG_LOGIN_REJECTED = "login_rejected";

    private final KordonApi api;
    private final SettingsHolder settings;
    private final ConnectionStateHolder state;
    private final ThrottledLog log;
    private final Messages messages;
    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);
    private final PendingLogins pending;
    private final RecentAllows recent;

    public LoginGate(KordonApi api, SettingsHolder settings, ConnectionStateHolder state, ThrottledLog log,
                     Clock clock) {
        this(api, settings, state, log, clock, Messages.load());
    }

    public LoginGate(KordonApi api, SettingsHolder settings, ConnectionStateHolder state, ThrottledLog log,
                     Clock clock, Messages messages) {
        this.api = api;
        this.settings = settings;
        this.state = state;
        this.log = log;
        this.messages = messages;
        this.pending = new PendingLogins(clock);
        this.recent = new RecentAllows(clock);
    }

    /** Returns at once; the future never completes exceptionally and completes within {@link #TIMEOUT}. */
    public CompletableFuture<LoginVerdict> check(LoginAttempt a) {
        try {
            ConnectionState now = state.get();
            if (now == ConnectionState.NOT_CONFIGURED) return done(allow(LoginVerdict.Source.NOT_CONFIGURED));
            if (now == ConnectionState.DISABLED) return done(allow(LoginVerdict.Source.ACCOUNT_DISABLED));
            Optional<LoginVerdict> reused = recent.find(a);
            if (reused.isPresent()) return done(reused.get());
            if (!permits.tryAcquire()) return done(fallback(a, CAUSE_OVERLOAD));
            CompletableFuture<ApiResult<LoginResponse>> sent;
            try {
                sent = api.login(request(a));
            } catch (Throwable t) {
                permits.release();
                return done(fallback(a, CAUSE_NETWORK));
            }
            CompletableFuture<LoginVerdict> verdict = new CompletableFuture<>();
            sent.whenComplete((result, error) -> {
                permits.release(); // the request is over (the HTTP layer caps it as well)
                verdict.complete(map(a, result, error));
            });
            verdict.orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return verdict.handle((v, error) -> v != null ? v
                    : fallback(a, error instanceof TimeoutException ? CAUSE_TIMEOUT : CAUSE_NETWORK));
        } catch (Throwable t) {
            log.debug(() -> "login check failed: " + t);
            return done(fallback(a, CAUSE_NETWORK));
        }
    }

    /**
     * The verdict when the check is unavailable: allow when failOpen is set, otherwise block with the "check failed"
     * text. Used by the watchdog, on errors and during stop.
     */
    public LoginVerdict fallback(LoginAttempt a) {
        return fallback(a, CAUSE_TIMEOUT);
    }

    /** {@link #fallback(LoginAttempt)} with the local cause the reporter prints. */
    public LoginVerdict fallback(LoginAttempt a, String cause) {
        ServerSettings s = settings.get();
        List<String> reasons = List.of(cause == null ? CAUSE_TIMEOUT : cause);
        if (s.failOpen()) {
            return new LoginVerdict(true, null, LoginVerdict.Source.FAIL_OPEN, null, null, reasons, false, null);
        }
        String notice = s.messages().blockedNotice();
        return new LoginVerdict(false, Legacy.translate(Placeholders.fill(s.messages().checkFailed(), a.name(), a.ip())),
                LoginVerdict.Source.FAIL_CLOSED, null, null, reasons, false, null,
                notice == null || notice.isEmpty() ? notice : Placeholders.fill(notice, a.name(), a.ip()));
    }

    /** The final verdict allowed the player: remember the pending loginId and the {@link RecentAllows} entry. */
    public void onLoginAllowed(LoginAttempt a, LoginVerdict finalVerdict) {
        if (a == null || finalVerdict == null) return;
        pending.put(a.connectionKey(), finalVerdict.loginId(), RecentAllows.Key.of(a));
        recent.remember(a, finalVerdict);
    }

    /** The login was denied or aborted: forget what was remembered for this connection. */
    public void onLoginAborted(Object connectionKey) {
        pending.remove(connectionKey);
    }

    /**
     * The server key changed: a verdict or loginId of the previous server must never be reused, so every remembered
     * allow and pending loginId is dropped.
     */
    public void forgetVerdicts() {
        recent.clear();
        pending.clear();
    }

    /** At PostLogin: takes the loginId of this connection's allowed verdict and its reuse entry; nullable. */
    public Long takeLoginId(Object connectionKey) {
        PendingLogins.Entry e = pending.takeEntry(connectionKey);
        if (e == null) return null;
        recent.consume(e.key());
        return e.loginId();
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────────────────────────────────

    private LoginVerdict map(LoginAttempt a, ApiResult<LoginResponse> result, Throwable error) {
        if (error != null || result == null) return fallback(a, CAUSE_NETWORK);
        return switch (result.outcome()) {
            case OK -> panelVerdict(a, result.body());
            case ACCOUNT_DISABLED -> {
                // Everyone is let in and no /login is sent until a heartbeat succeeds; the heartbeat logs the reason.
                state.set(ConnectionState.DISABLED);
                yield allow(LoginVerdict.Source.ACCOUNT_DISABLED);
            }
            case NOT_CONFIGURED -> allow(LoginVerdict.Source.NOT_CONFIGURED);
            case TIMEOUT -> fallback(a, CAUSE_TIMEOUT);
            case NETWORK -> fallback(a, CAUSE_NETWORK);
            case SERVER_ERROR -> fallback(a, CAUSE_SERVER_ERROR);
            case BAD_REQUEST -> rejected(a, result.errorMessage());
            case UNAUTHORIZED -> fallback(a, CAUSE_UNAUTHORIZED);
            case REDIRECT -> fallback(a, CAUSE_REDIRECT);
            case RATE_LIMITED -> fallback(a, CAUSE_RATE_LIMITED);
            case TOO_LARGE -> fallback(a, CAUSE_TOO_LARGE);
            case UNSUPPORTED, COMMANDS_DISABLED, INVALID_VALUE -> fallback(a, CAUSE_UNSUPPORTED);
        };
    }

    /**
     * A 400: the panel refused this request, for example over a nick it does not accept. This is not an outage, so the
     * log names the player and the panel's reason instead of a generic "no answer". The verdict is still the fallback.
     */
    private LoginVerdict rejected(LoginAttempt a, String panelMessage) {
        LoginVerdict verdict = fallback(a, CAUSE_BAD_REQUEST);
        try {
            String reason = panelMessage == null || panelMessage.isBlank()
                    ? messages.english().get(MessageKey.CAUSE_BAD_REQUEST) : panelMessage.strip();
            if (reason.length() > MAX_REJECTION_TEXT) reason = reason.substring(0, MAX_REJECTION_TEXT) + "…";
            log.warn(LOG_LOGIN_REJECTED, messages.console(MessageKey.LOG_LOGIN_REJECTED,
                    "name", Messages.literal(a.name()), "error", Messages.literal(reason),
                    "failOpenAction", messages.english().get(MessageKey.failOpenAction(verdict.allow()))));
        } catch (RuntimeException e) {
            log.debug(() -> "login rejection not logged: " + e);
        }
        return verdict;
    }

    private LoginVerdict panelVerdict(LoginAttempt a, LoginResponse body) {
        boolean allow = Boolean.TRUE.equals(body.allow());
        String kick = allow ? null : Legacy.translate(Placeholders.fill(kickText(body), a.name(), a.ip()));
        return new LoginVerdict(allow, kick, LoginVerdict.Source.PANEL, body.result(), body.decidedBy(),
                clean(body.reasons()), body.wouldBlock(), body.loginId(), blankToNull(body.notice()),
                LocalizedLine.languages(body.notices()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * The panel's text; if it sent none, the matching text from the last heartbeat. A Kordon-wide block
     * ({@code kordon_base}) reads as a VPN block for a connection category and as a blacklist block otherwise.
     */
    private String kickText(LoginResponse body) {
        if (body.message() != null && !body.message().isBlank()) return body.message();
        ServerSettings.Messages m = settings.get().messages();
        String text = switch (body.decidedBy() == null ? "" : body.decidedBy()) {
            case "blacklist" -> m.blacklist();
            case "country" -> m.country();
            case "error" -> m.checkFailed();
            case "kordon_base" -> connectionCategory(body.reasons()) ? m.vpn() : m.blacklist();
            default -> m.vpn();
        };
        return text == null || text.isBlank() ? m.checkFailed() : text;
    }

    /** Whether the first {@code kordon_base:<category>} reason names a connection category. */
    private static boolean connectionCategory(List<String> reasons) {
        if (reasons == null) return false;
        for (String reason : reasons) {
            if (reason == null) continue;
            String code = reason.strip().toLowerCase(Locale.ROOT);
            if (code.startsWith("kordon_base:")) {
                return BASE_CONNECTION_CATEGORIES.contains(code.substring("kordon_base:".length()));
            }
        }
        return false;
    }

    private static LoginRequest request(LoginAttempt a) {
        LoginRequest.Skin skin = a.skin() == null ? null : new LoginRequest.Skin(a.skin().texture(), a.skin().model());
        return new LoginRequest(a.name(), a.ip(), a.uuid() == null ? null : a.uuid().toString(),
                a.protocol() > 0 ? a.protocol() : null, skin, a.host(), a.bedrock() ? Boolean.TRUE : null);
    }

    private static LoginVerdict allow(LoginVerdict.Source source) {
        return new LoginVerdict(true, null, source, null, null, List.of(), false, null);
    }

    private static List<String> clean(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(reasons.size());
        for (String r : reasons) {
            if (r != null) out.add(r);
        }
        return Collections.unmodifiableList(out);
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    int inFlight() {
        return MAX_IN_FLIGHT - permits.availablePermits();
    }
}
